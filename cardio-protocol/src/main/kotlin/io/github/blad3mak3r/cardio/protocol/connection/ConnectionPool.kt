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
        val registry: TypeCodecRegistry = TypeCodecRegistry.default(),
    )

    class PooledConnection internal constructor(
        internal val inner: Connection,
        private val pool: ConnectionPool
    ) {
        private var released = false

        internal val acquiredAt: Long = System.currentTimeMillis()

        suspend fun <T> query(
            sql: String,
            vararg params: Param<*>,
            mapper: (Row) -> T
        ) = inner.query(sql, *params, mapper = mapper)

        suspend fun execute(
            sql: String,
            vararg params: Param<*>
        ) = inner.execute(sql, *params)

        suspend fun beginTransaction()    = inner.beginTransaction()
        suspend fun commitTransaction()   = inner.commitTransaction()
        suspend fun rollbackTransaction() = inner.rollbackTransaction()

        val isReady:  Boolean get() = inner.isReady
        val isFailed: Boolean get() = inner.isFailed

        suspend fun release() {
            if (released) return
            released = true
            pool.release(this)
        }
    }

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

    private val idlePool = Channel<PooledConnection>(capacity = configuration.maxSize)

    @Volatile private var closed = false

    val stats: Stats
        get() {
            val activeConnections = configuration.maxSize - semaphore.availablePermits
            return Stats(
                totalConnections  = totalConns.get(),
                activeConnections = activeConnections,
                idleConnections   = totalConns.get() - (activeConnections),
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

    suspend fun <T> use(block: suspend (PooledConnection) -> T): T {
        val conn: PooledConnection = acquire()
        return try {
            block(conn)
        } catch (e: Exception) {
            if (conn.isFailed) destroy(conn) else conn.release()
            throw e
        } finally {
            conn.release()
        }
    }

    suspend fun <T> transaction(block: suspend (PooledConnection) -> T): T = use { conn ->
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
        for (conn in idlePool) {
            runCatching { conn.inner.close() }
            totalConns.decrementAndGet()
            totalDestroyed.incrementAndGet()
        }
    }

    internal suspend fun acquire(): PooledConnection {
        check(!closed) { "Connection pool is closed" }
        pendingAcquires.incrementAndGet()
        return try {
            withTimeout(configuration.acquireTimeout) {
                semaphore.withPermit { getOrCreate() }
            }
        } catch (_: TimeoutCancellationException) {
            throw PgPoolTimeoutException(
                configuration.acquireTimeout, configuration.maxSize, pendingAcquires.get() - 1
            )
        } finally {
            pendingAcquires.decrementAndGet()
        }
    }

    internal suspend fun release(conn: PooledConnection) {
        totalReleased.incrementAndGet()

        if (closed || conn.isFailed) {
            destroy(conn)
            return
        }

        // Try to return to the idle channel; if full, destroy
        if (!idlePool.trySend(conn).isSuccess) {
            destroy(conn)
        }

        semaphore.release()
    }

    private suspend fun getOrCreate(): PooledConnection {
        // 1. Reuse idle connection if it exists and is healthy
        val idle = idlePool.tryReceive().getOrNull()
        if (idle != null) {
            if (idle.isReady) {
                totalAcquired.incrementAndGet()
                return PooledConnection(idle.inner, this)
            }
            // Dead idle connection — destroy and create a new one
            destroyQuietly(idle)
        }
        // 2. Create new
        return createConnection()
    }

    private suspend fun createConnection(): PooledConnection {
        var lastEx: Exception? = null
        repeat(configuration.maxReconnectAttempts) { attempt ->
            try {
                val conn = Connection.connect(configuration.connect, configuration.registry)
                totalConns.incrementAndGet()
                totalCreated.incrementAndGet()
                totalAcquired.incrementAndGet()
                return PooledConnection(conn, this)
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

    private suspend fun destroy(conn: PooledConnection) {
        runCatching { conn.inner.close() }
        totalConns.decrementAndGet()
        totalDestroyed.incrementAndGet()
        // Replenish if we fall below minSize
        if (totalConns.get() < configuration.minSize && !closed) {
            scope.launch { replenish() }
        }
    }

    private fun destroyQuietly(conn: PooledConnection) {
        scope.launch { destroy(conn) }
    }

    private suspend fun warmUp() {
        (0 until configuration.minSize).map {
            scope.async {
                runCatching {
                    val conn   = Connection.connect(configuration.connect, configuration.registry)
                    val pooled = PooledConnection(conn, this@ConnectionPool)
                    totalConns.incrementAndGet()
                    totalCreated.incrementAndGet()
                    idlePool.trySend(pooled)
                }
            }
        }.awaitAll()
    }

    private suspend fun replenish() {
        if (totalConns.get() >= configuration.minSize) return
        runCatching {
            val conn   = Connection.connect(configuration.connect, configuration.registry)
            val pooled = PooledConnection(conn, this)
            totalConns.incrementAndGet()
            totalCreated.incrementAndGet()
            if (!idlePool.trySend(pooled).isSuccess) destroy(pooled)
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

            toCheck.map { conn ->
                scope.async {
                    val alive = runCatching {
                        conn.query("SELECT 1") { }
                        true
                    }.getOrDefault(false)

                    if (alive) idlePool.trySend(conn)
                    else {
                        destroy(conn)
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

            for (conn in toInspect) {
                val idleMs = now - conn.acquiredAt
                if (idleMs >= maxIdleMs && totalConns.get() > configuration.minSize) destroy(conn)
                else idlePool.trySend(conn)
            }
        }
    }
}