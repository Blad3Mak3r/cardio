package io.github.blad3mak3r.cardio.protocol.connection

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutine-based connection pool that manages a fixed-size set of [Connection] instances.
 *
 * The pool:
 * - Warms up to [Configuration.minSize] connections on creation.
 * - Limits concurrency to [Configuration.maxSize] via a [kotlinx.coroutines.sync.Semaphore].
 * - Reuses idle connections from an internal channel; creates new ones on demand.
 * - Runs background tasks for health-checking and idle-connection eviction.
 * - Automatically replenishes connections when the count drops below [Configuration.minSize].
 *
 * Obtain a connection with [use] (raw access) or [transaction] (automatic BEGIN/COMMIT/ROLLBACK).
 * The pool-level [query] and [execute] shortcuts delegate to [use] without an explicit transaction.
 *
 * All public methods are `suspend` and coroutine-safe. Multiple coroutines may acquire connections
 * concurrently; the semaphore ensures that no more than [Configuration.maxSize] are active at once.
 *
 * @param configuration Pool and connection settings.
 * @param scope         Coroutine scope used for background tasks. Defaults to a scope backed by
 *                      [kotlinx.coroutines.Dispatchers.IO] + [kotlinx.coroutines.SupervisorJob].
 *
 * @see Configuration
 * @see Stats
 */
class ConnectionPool(
    val configuration: Configuration,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private val logger = LoggerFactory.getLogger("ConnectionPool")
    }

    /**
     * Pool and connection configuration.
     *
     * @param connect                Underlying [Connection.Configuration] used for every new connection.
     * @param maxSize                Maximum number of simultaneous connections. Defaults to `10`.
     * @param minSize                Minimum number of connections kept alive (warm-up / replenishment). Defaults to `2`.
     * @param acquireTimeout         Maximum time a caller waits for a connection before [PgPoolTimeoutException] is thrown. Defaults to 30 seconds.
     * @param idleTimeout            Time after which an idle connection is eligible for eviction by the idle reaper. Defaults to 600 seconds.
     * @param healthCheckInterval    Interval between background health-check sweeps. Defaults to 30 seconds.
     * @param maxReconnectAttempts   Number of attempts to create a new connection before raising [PgConnectionCreationException]. Defaults to `5`.
     * @param reconnectBackoff       Base back-off duration between reconnect attempts (exponentially doubled per attempt). Defaults to 500 ms.
     * @param registry               Codec registry shared by all connections in the pool. Defaults to [TypeCodecRegistry.Default].
     */
    data class Configuration(
        val connect: Connection.Configuration,

        val maxSize: Int = 10,
        val minSize: Int = 2,
        val acquireTimeout: Duration = 30.seconds,
        val idleTimeout: Duration = 600.seconds,
        val healthCheckInterval: Duration = 30.seconds,
        val maxReconnectAttempts: Int = 5,
        val reconnectBackoff: Duration = 500.milliseconds,
        val registry: TypeCodecRegistry = TypeCodecRegistry.Default,
    ) {
        init {
            require(maxSize > 0) { "maxSize must be > 0" }
            require(minSize >= 0) { "minSize must be >= 0" }
            require(minSize <= maxSize) { "minSize ($minSize) must be <= maxSize ($maxSize)" }
        }
    }

    /** Pairs a connection with the timestamp at which it was placed into the idle pool. */
    private data class IdleEntry(val conn: Connection, val idleSince: Long)

    /**
     * Snapshot of the pool's current operational statistics.
     *
     * @param totalConnections  Total number of open connections (active + idle).
     * @param activeConnections Number of connections currently checked out inside a [use] block.
     * @param idleConnections   Number of connections currently waiting in the idle pool.
     * @param pendingAcquires   Number of callers currently waiting to acquire a connection.
     * @param totalAcquired     Cumulative count of times a connection was handed out since pool creation.
     * @param totalReleased     Cumulative count of times a connection was returned to the idle pool.
     * @param totalCreated      Cumulative count of connections created since pool creation.
     * @param totalDestroyed    Cumulative count of connections destroyed (evicted, failed, or closed) since pool creation.
     * @param totalErrors       Cumulative count of connection-creation errors since pool creation.
     */
    data class Stats(
        val totalConnections:  Int,
        val activeConnections: Int,
        val idleConnections:   Int,
        val pendingAcquires:   Int,
        val totalAcquired:     Long,
        val totalReleased:     Long,
        val totalCreated:      Long,
        val totalDestroyed:    Long,
        val totalErrors:       Long,
    )

    private val totalAcquired  = AtomicLong(0)
    private val totalReleased  = AtomicLong(0)
    private val totalCreated   = AtomicLong(0)
    private val totalDestroyed = AtomicLong(0)
    private val totalErrors    = AtomicLong(0)
    private val totalConns     = AtomicInteger(0)
    private val pendingAcquires = AtomicInteger(0)

    private val semaphore = Semaphore(configuration.maxSize)

    private val idlePool = Channel<IdleEntry>(capacity = configuration.maxSize)

    private val closed = AtomicBoolean(false)

    /** Tracks connections that are currently inside a `use {}` block, for clean shutdown. */
    private val activeConnections: MutableSet<Connection> = ConcurrentHashMap.newKeySet()

    /**
     * Tracks ALL live connections regardless of state (idle, active, or being processed by
     * background tasks). This is the authoritative set used by [close] to ensure nothing is
     * leaked when the scope is cancelled while the health-check or idle-reaper hold connections
     * outside of [idlePool].
     */
    private val allConnections: MutableSet<Connection> = ConcurrentHashMap.newKeySet()

    /** Returns a point-in-time snapshot of the pool's operational statistics. */
    val stats: Stats
        get() {
            val total  = totalConns.get()
            val active = configuration.maxSize - semaphore.availablePermits
            return Stats(
                totalConnections  = total,
                activeConnections = active,
                idleConnections   = maxOf(0, total - active),
                pendingAcquires   = pendingAcquires.get(),
                totalAcquired     = totalAcquired.get(),
                totalReleased     = totalReleased.get(),
                totalCreated      = totalCreated.get(),
                totalDestroyed    = totalDestroyed.get(),
                totalErrors       = totalErrors.get(),
            )
        }

    init {
        scope.launch { warmUp() }
        scope.launch { runHealthCheck() }
        scope.launch { runIdleReaper() }
    }

    /**
     * Acquires a connection from the pool, executes [block] with it, then returns the
     * connection to the idle pool (or destroys it if it is in a failed state).
     *
     * Waits up to [Configuration.acquireTimeout] for a free connection slot.
     *
     * @param block Suspending lambda that receives the acquired [Connection].
     * @return The value returned by [block].
     * @throws PgPoolTimeoutException if no connection becomes available within [Configuration.acquireTimeout].
     * @throws IllegalStateException  if the pool has already been [close]d.
     */
    suspend fun <T> use(block: suspend (Connection) -> T): T {
        pendingAcquires.incrementAndGet()
        return try {
            withTimeout(configuration.acquireTimeout) {
                semaphore.withPermit {
                    check(!closed.get()) { "Connection pool is closed" }
                    val conn = getOrCreate()
                    try {
                        block(conn)
                    } finally {
                        returnToPool(conn)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw PgPoolTimeoutException(
                configuration.acquireTimeout, configuration.maxSize, pendingAcquires.get() - 1
            )
        } finally {
            pendingAcquires.decrementAndGet()
        }
    }

    /**
     * Acquires a connection, begins a PostgreSQL transaction (`BEGIN`), runs [block], and
     * commits the transaction on success.  If [block] throws any exception the transaction is
     * automatically rolled back and the exception re-thrown.
     *
     * @param block Suspending lambda that receives the [Connection] inside an active transaction.
     * @return The value returned by [block].
     * @throws PgPoolTimeoutException if a connection cannot be acquired in time.
     */
    suspend fun <T> transaction(block: suspend (Connection) -> T): T = use { conn ->
        conn.beginTransaction()
        try {
            val result = block(conn)
            conn.commitTransaction()
            result
        } catch (e: Exception) {
            runCatching { conn.rollbackTransaction() }
            throw e
        }
    }

    /**
     * Acquires a connection and executes [sql] as a query without an explicit transaction.
     *
     * @param sql    PostgreSQL query using positional parameters (`$1`, `$2`, …).
     * @param params Parameter values in the same order as the positional placeholders.
     * @param mapper Row-to-result transformation applied to each row.
     * @return List of values produced by [mapper] for every result row.
     */
    suspend fun <T> query(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T,
    ): List<T> = use { it.query(sql = sql, params = params, mapper = mapper) }

    /**
     * Acquires a connection and executes [sql] as a DML/DDL command without an explicit transaction.
     *
     * @param sql    PostgreSQL statement using positional parameters (`$1`, `$2`, …).
     * @param params Parameter values in the same order as the positional placeholders.
     * @return Number of rows affected by the statement.
     */
    suspend fun execute(
        sql: String,
        params: List<Any?> = emptyList(),
    ): Long = use { it.execute(sql = sql, params = params) }

    /**
     * Acquires a connection and executes [sql] as a DML statement with a `RETURNING` clause.
     *
     * @param sql    PostgreSQL statement with `RETURNING` using positional parameters.
     * @param params Parameter values in the same order as the positional placeholders.
     * @param mapper Row-to-result transformation applied to each returned row.
     * @return List of values produced by [mapper] for every returned row.
     */
    suspend fun <T> executeReturning(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T,
    ): List<T> = use { it.executeReturning(sql = sql, params = params, mapper = mapper) }

    /**
     * Returns a cold [Flow] that streams query results using a wire-level cursor.
     *
     * A connection is exclusively borrowed for the entire duration of flow collection
     * and released in the `finally` block, regardless of cancellation or error.
     *
     * @param sql       PostgreSQL query using positional parameters.
     * @param params    Parameter values.
     * @param chunkSize Number of rows fetched per `Execute` round-trip. Defaults to `100`.
     * @param mapper    Row-to-result transformation.
     * @return A cold [Flow] emitting mapped values as rows arrive from the server.
     */
    fun <T> queryFlow(
        sql: String,
        params: List<Any?> = emptyList(),
        chunkSize: Int = 100,
        mapper: (Row) -> T,
    ): Flow<T> = flow {
        val conn = borrowConnection()
        try {
            conn.queryFlow(sql = sql, params = params, chunkSize = chunkSize, mapper = mapper)
                .collect { emit(it) }
        } finally {
            returnConnection(conn)
        }
    }

    /**
     * Verifies that at least one connection can be established by acquiring and immediately
     * releasing a connection. Throws [PgConnectionCreationException] (or the underlying cause)
     * if the database is unreachable or credentials are wrong.
     */
    suspend fun probe() = query("SELECT version()") { it.get<String>(0) }.let {
        logger.info("Successfully connected to PostgreSQL version: $it")
    }

    /** Closes all connections and stops background tasks. */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        idlePool.close()
        // Snapshot allConnections — this covers idle entries still in the channel,
        // connections currently inside a use {} block, AND connections that were
        // pulled out of idlePool by the health-check or idle-reaper coroutines
        // and would be leaked if we only drained the channel + activeConnections.
        val snapshot = allConnections.toSet()
        allConnections.clear()
        activeConnections.clear()
        for (conn in snapshot) {
            runCatching { conn.close() }
            totalConns.decrementAndGet()
            totalDestroyed.incrementAndGet()
        }
    }

    /**
     * Acquires a connection from the pool for exclusive use by a [Flow] collector.
     *
     * Unlike [use], this does NOT wrap the operation in a timeout for the collection phase —
     * only the initial acquire is bounded by [Configuration.acquireTimeout].
     * Callers **must** call [returnConnection] in a `finally` block.
     */
    internal suspend fun borrowConnection(): Connection {
        pendingAcquires.incrementAndGet()
        try {
            withTimeout(configuration.acquireTimeout) {
                semaphore.acquire()
            }
        } catch (e: TimeoutCancellationException) {
            pendingAcquires.decrementAndGet()
            throw PgPoolTimeoutException(
                configuration.acquireTimeout, configuration.maxSize, pendingAcquires.get()
            )
        }
        pendingAcquires.decrementAndGet()
        check(!closed.get()) { "Connection pool is closed" }
        return getOrCreate()
    }

    /**
     * Returns a connection previously obtained via [borrowConnection] to the idle pool
     * and releases the semaphore permit.
     */
    internal suspend fun returnConnection(conn: Connection) {
        try {
            returnToPool(conn)
        } finally {
            semaphore.release()
        }
    }

    private suspend fun getOrCreate(): Connection {
        // 1. Reuse idle connections — loop to skip over any dead entries
        while (true) {
            val idle = idlePool.tryReceive().getOrNull() ?: break
            if (idle.conn.isReady) {
                activeConnections += idle.conn
                totalAcquired.incrementAndGet()
                return idle.conn
            }
            // Dead idle connection — destroy it and try the next one
            destroyQuietly(idle.conn)
        }
        // 2. No healthy idle connection found — create a new one
        val conn = createConnection()
        activeConnections += conn
        return conn
    }

    private suspend fun createConnection(): Connection {
        var lastEx: Exception? = null
        repeat(configuration.maxReconnectAttempts) { attempt ->
            try {
                val conn = Connection.connect(configuration.connect, configuration.registry)
                allConnections += conn
                totalConns.incrementAndGet()
                totalCreated.incrementAndGet()
                totalAcquired.incrementAndGet()
                return conn
            } catch (e: Exception) {
                lastEx = e
                if (attempt < configuration.maxReconnectAttempts - 1) {
                    delay(configuration.reconnectBackoff.inWholeMilliseconds * (1L shl attempt))
                }
            }
        }
        totalErrors.incrementAndGet()
        throw PgConnectionCreationException(
            configuration.connect.host, configuration.connect.port,
            configuration.maxReconnectAttempts, lastEx!!
        )
    }

    /** Returns `conn` to the idle pool, or destroys it if the pool is closed/full. Never touches the semaphore. */
    private suspend fun returnToPool(conn: Connection) {
        activeConnections -= conn
        totalReleased.incrementAndGet()
        if (closed.get() || conn.isFailed) {
            destroyInner(conn)
            return
        }
        // Try to return to the idle channel; if full, destroy
        if (!idlePool.trySend(IdleEntry(conn, System.currentTimeMillis())).isSuccess) {
            destroyInner(conn)
        }
    }

    /** Closes the underlying connection and updates counters. Never touches the semaphore. */
    private suspend fun destroyInner(conn: Connection) {
        runCatching { conn.close() }
        allConnections -= conn
        totalConns.decrementAndGet()
        totalDestroyed.incrementAndGet()
        // Replenish if we fall below minSize
        if (totalConns.get() < configuration.minSize && !closed.get()) {
            scope.launch { replenish() }
        }
    }

    private fun destroyQuietly(conn: Connection) {
        scope.launch { destroyInner(conn) }
    }

    private suspend fun warmUp() {
        (0 until configuration.minSize).map {
            scope.async {
                runCatching {
                    val conn = Connection.connect(configuration.connect, configuration.registry)
                    allConnections += conn
                    totalConns.incrementAndGet()
                    totalCreated.incrementAndGet()
                    if (!idlePool.trySend(IdleEntry(conn, System.currentTimeMillis())).isSuccess) {
                        destroyInner(conn)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun replenish() {
        if (totalConns.get() >= configuration.minSize) return
        runCatching {
            val conn = Connection.connect(configuration.connect, configuration.registry)
            allConnections += conn
            totalConns.incrementAndGet()
            totalCreated.incrementAndGet()
            if (!idlePool.trySend(IdleEntry(conn, System.currentTimeMillis())).isSuccess) destroyInner(conn)
        }
    }

    /** Checks each idle connection and discards dead ones. */
    private suspend fun runHealthCheck() {
        while (!closed.get()) {
            delay(configuration.healthCheckInterval)
            if (closed.get()) break

            val toCheck = buildList {
                while (true) add(idlePool.tryReceive().getOrNull() ?: break)
            }

            toCheck.map { entry ->
                scope.async {
                    val alive = runCatching {
                        entry.conn.query("SELECT 1") { }
                        true
                    }.getOrDefault(false)

                    if (alive) {
                        // Return to pool; destroy if it no longer fits (pool resized / closed)
                        if (!idlePool.trySend(entry).isSuccess) destroyInner(entry.conn)
                    } else {
                        destroyInner(entry.conn)
                        if (totalConns.get() < configuration.minSize) replenish()
                    }
                }
            }.awaitAll()
        }
    }

    /** Closes idle connections that have been unused for too long. */
    private suspend fun runIdleReaper() {
        while (!closed.get()) {
            delay(configuration.idleTimeout / 2)
            if (closed.get() || totalConns.get() <= configuration.minSize) continue

            val now       = System.currentTimeMillis()
            val maxIdleMs = configuration.idleTimeout.inWholeMilliseconds

            // Drain the entire idle pool for inspection
            val toInspect = buildList {
                while (true) add(idlePool.tryReceive().getOrNull() ?: break)
            }

            for (entry in toInspect) {
                val idleMs = now - entry.idleSince
                if (idleMs >= maxIdleMs && totalConns.get() > configuration.minSize) {
                    destroyInner(entry.conn)
                } else {
                    // Return to pool; destroy if channel is full to avoid leaking
                    if (!idlePool.trySend(entry).isSuccess) destroyInner(entry.conn)
                }
            }
        }
    }
}
