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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionPool(
    private val configuration: Configuration,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
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

    suspend fun <T> use(block: suspend (Connection) -> T): T {
        check(!closed.get()) { "Connection pool is closed" }
        pendingAcquires.incrementAndGet()
        return try {
            withTimeout(configuration.acquireTimeout) {
                semaphore.withPermit {
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

    /** Shortcut: query without explicit transaction. */
    suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T,
    ): List<T> = use { it.query(sql, *params, mapper = mapper) }

    /** Shortcut: execute without explicit transaction. */
    suspend fun execute(
        sql: String,
        vararg params: Any?,
    ): Long = use { it.execute(sql, *params) }

    /** Closes all connections and stops background tasks. */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        idlePool.close()
        // Drain and close idle connections
        for (entry in idlePool) {
            runCatching { entry.conn.close() }
            totalConns.decrementAndGet()
            totalDestroyed.incrementAndGet()
        }
        // Close connections that are currently in-flight inside a use {} block
        for (conn in activeConnections) {
            runCatching { conn.close() }
            totalConns.decrementAndGet()
            totalDestroyed.incrementAndGet()
        }
        activeConnections.clear()
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