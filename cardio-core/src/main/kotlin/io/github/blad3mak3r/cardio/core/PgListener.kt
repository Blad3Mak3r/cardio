package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.PgNotification
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import io.github.blad3mak3r.cardio.protocol.connection.ConnectionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages a single dedicated PostgreSQL connection for asynchronous `LISTEN`/`NOTIFY` support.
 *
 * `PgListener` owns its connection outside the pool — it holds the connection open indefinitely
 * while waiting for notifications, and automatically reconnects with exponential backoff on
 * failure, re-subscribing to all active channels.
 *
 * ## Usage
 * ```kotlin
 * val listener = PgListener.connect {
 *     host     = "localhost"
 *     database = "mydb"
 *     username = "user"
 *     password = "secret"
 * }
 *
 * listener.listen("orders")
 *
 * // Collect all notifications:
 * listener.notifications.collect { n -> println("${n.channel}: ${n.payload}") }
 *
 * // Or filter to one channel:
 * listener.channel("orders").collect { n -> handleOrder(n.payload) }
 *
 * // Sending a notification from the main connection:
 * db.notify("orders", "new-order-42")
 *
 * listener.close()
 * ```
 *
 * @see PgNotification
 * @see Cardio.notify
 */
class PgListener private constructor(
    private val connectionConfig: Connection.Configuration,
    private val registry: TypeCodecRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notifications = MutableSharedFlow<PgNotification>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Hot [SharedFlow] that emits every notification received on any active channel.
     * Multiple collectors are supported; each receives the same notification.
     */
    val notifications: SharedFlow<PgNotification> = _notifications.asSharedFlow()

    private val mutex = Mutex()
    private val active = LinkedHashSet<String>()
    private var loopJob: Job? = null

    /**
     * Returns a [Flow] that emits only notifications whose channel name equals [channel].
     *
     * This is a filtered view of [notifications]; all collectors on the same listener share
     * the same underlying connection.
     */
    fun channel(channel: String): Flow<PgNotification> =
        notifications.filter { it.channel == channel }

    /**
     * Subscribes to one or more [channels].
     *
     * Channels that are already active are ignored.  If any new channel is added, the listener
     * reconnects and re-issues `LISTEN` for all active channels.
     */
    suspend fun listen(vararg channels: String): Unit = mutex.withLock {
        val added = channels.filter { active.add(it) }
        if (added.isNotEmpty()) restartLoop()
    }

    /**
     * Unsubscribes from one or more [channels].
     *
     * Channels that are not currently active are ignored.  If any channel is removed, the
     * listener reconnects with the remaining subscriptions (or stops if none remain).
     */
    suspend fun unlisten(vararg channels: String): Unit = mutex.withLock {
        val removed = channels.filter { active.remove(it) }
        if (removed.isNotEmpty()) restartLoop()
    }

    /**
     * Closes the listener, cancels the receive loop, and closes the dedicated connection.
     */
    suspend fun close() {
        scope.cancel()
        loopJob?.join()
    }

    // Must be called while holding `mutex`.
    private suspend fun restartLoop() {
        loopJob?.cancelAndJoin()

        if (active.isEmpty()) {
            loopJob = null
            return
        }

        val channels = active.toList()

        loopJob = scope.launch {
            var backoffMs = 500L
            while (isActive) {
                try {
                    val conn = Connection.connect(connectionConfig, registry)
                    try {
                        for (ch in channels) conn.execute("LISTEN ${quoteIdent(ch)}")
                        conn.notificationLoop { notif -> _notifications.emit(notif) }
                    } finally {
                        conn.close()
                    }
                    backoffMs = 500L
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, 30_000L)
                }
            }
        }
    }

    companion object {
        /**
         * Creates a [PgListener] that reuses the connection configuration of an existing
         * [ConnectionPool].  Intended to be called from [Cardio.newListener].
         */
        internal fun fromPool(config: ConnectionPool.Configuration): PgListener =
            PgListener(config.connect, config.registry)

        /**
         * Creates a [PgListener] using the same DSL as [Cardio.new].
         *
         * The listener starts with no channel subscriptions.  Call [listen] to begin
         * receiving notifications.
         *
         * ```kotlin
         * val listener = PgListener.connect {
         *     host = "localhost"; database = "db"; username = "u"; password = "p"
         * }
         * listener.listen("my_channel")
         * ```
         */
        fun connect(block: Cardio.Configuration.() -> Unit): PgListener {
            val cfg = Cardio.Configuration().apply(block)
            val poolCfg = cfg.buildPoolConfig()
            return PgListener(poolCfg.connect, poolCfg.registry)
        }

        private fun quoteIdent(name: String): String = '"' + name.replace("\"", "\"\"") + '"'
    }
}
