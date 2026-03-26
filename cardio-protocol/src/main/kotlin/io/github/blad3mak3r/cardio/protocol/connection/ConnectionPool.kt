package io.github.blad3mak3r.cardio.protocol.connection

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.Param
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
    )

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

    @Volatile private var closed = false

    val stats: Stats
        get() {
            val activeConnections = configuration.maxSize - semaphore.availablePermits
            return Stats(
                totalConnections  = totalConns.get(),
                activeConnections = activeConnections,
                idleConnections   = totalConns.get() - activeConnections,
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
        check(!closed) { "Connection pool is closed" }
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
        vararg params: Param<*>,
        mapper: (Row) -> T,
    ): List<T> = use { it.query(sql, *params, mapper = mapper) }

    /** Shortcut: execute without explicit transaction. */
    suspend fun execute(
        sql: String,
        vararg params: Param<*>,
    ): Long = use { it.execute(sql, *params) }

    /** Closes all connections and stops background tasks. */
    suspend fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        idlePool.close()
        for (entry in idlePool) {
            runCatching { entry.conn.close() }
            totalConns.decrementAndGet()
            totalDestroyed.incrementAndGet()
        }
    }

    private suspend fun getOrCreate(): Connection {
        // 1. Reuse idle connection if it exists and is healthy
        val idle = idlePool.tryReceive().getOrNull()
        if (idle != null) {
            if (idle.conn.isReady) {
                totalAcquired.incrementAndGet()
                return idle.conn
            }
            // Dead idle connection — destroy and create a new one
            destroyQuietly(idle.conn)
        }
        // 2. Create new
        return createConnection()
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
        totalReleased.incrementAndGet()
        if (closed || conn.isFailed) {
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
        if (totalConns.get() < configuration.minSize && !closed) {
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
                    idlePool.trySend(IdleEntry(conn, System.currentTimeMillis()))
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
        while (!closed) {
            delay(configuration.healthCheckInterval)
            if (closed) break

            val toCheck = buildList {
                while (true) add(idlePool.tryReceive().getOrNull() ?: break)
            }

            toCheck.map { entry ->
                scope.async {
                    val alive = runCatching {
                        entry.conn.query("SELECT 1") { }
                        true
                    }.getOrDefault(false)

                    if (alive) idlePool.trySend(entry)
                    else {
                        destroyInner(entry.conn)
                        if (totalConns.get() < configuration.minSize) replenish()
                    }
                }
            }.awaitAll()
        }
    }

    /** Closes idle connections that have been unused for too long. */
    private suspend fun runIdleReaper() {
        while (!closed) {
            delay(configuration.idleTimeout / 2)
            if (closed || totalConns.get() <= configuration.minSize) continue

            val now       = System.currentTimeMillis()
            val maxIdleMs = configuration.idleTimeout.inWholeMilliseconds

            val toInspect = buildList {
                while (totalConns.get() > configuration.minSize) {
                    add(idlePool.tryReceive().getOrNull() ?: break)
                }
            }

            for (entry in toInspect) {
                val idleMs = now - entry.idleSince
                if (idleMs >= maxIdleMs && totalConns.get() > configuration.minSize) destroyInner(entry.conn)
                else idlePool.trySend(entry)
            }
        }
    }
}