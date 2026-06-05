package io.github.blad3mak3r.cardio.pgmq

import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Reactive consumer for a PGMQ queue.
 *
 * Holds a dedicated PostgreSQL connection outside the pool and continuously polls the queue
 * using `pgmq.read_with_poll`, which blocks server-side for up to [maxPollSeconds] per call.
 * Received messages are emitted on the [messages] [SharedFlow].
 *
 * Create via [Cardio.pgmqConsumer]. After processing each message, call [ack] (delete) or
 * [archive] to remove it from the queue. On shutdown, call [close].
 *
 * ```kotlin
 * val consumer = db.pgmqConsumer("orders", visibilityTimeout = 30)
 * consumer.messages.collect { msg ->
 *     processOrder(msg.message)
 *     consumer.ack(msg.msgId)
 * }
 * ```
 */
class PgmqConsumer private constructor(
    private val db: Cardio,
    private val connectionConfig: Connection.Configuration,
    private val registry: TypeCodecRegistry,
    val queueName: String,
    val visibilityTimeout: Int,
    val batchSize: Int,
    val maxPollSeconds: Int,
    val pollIntervalMs: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableSharedFlow<PgmqMessage>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot [SharedFlow] that emits every message received from the queue. */
    val messages: SharedFlow<PgmqMessage> = _messages.asSharedFlow()

    private val loopJob: Job = scope.launch { pollLoop() }

    private suspend fun pollLoop() {
        var backoffMs = 500L
        while (coroutineContext.isActive) {
            try {
                val conn = Connection.connect(connectionConfig, registry)
                try {
                    while (coroutineContext.isActive) {
                        val rows = conn.query(
                            "SELECT * FROM pgmq.read_with_poll(\$1, \$2, \$3, \$4, \$5)",
                            listOf(queueName, visibilityTimeout, batchSize, maxPollSeconds, pollIntervalMs),
                        ) { it.toPgmqMessage() }
                        rows.forEach { _messages.emit(it) }
                    }
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

    // ── Ack / Archive (via pool — fast operations) ────────────────────────────

    suspend fun ack(msgId: Long): Boolean =
        db.queryOne("SELECT pgmq.delete(\$1, \$2)", listOf(queueName, msgId)) { it.get<Boolean>(0) } ?: false

    suspend fun ackBatch(msgIds: List<Long>): List<Long> =
        db.query("SELECT * FROM pgmq.delete(\$1, \$2::bigint[])", listOf(queueName, msgIds)) { it.get<Long>(0) }

    suspend fun archive(msgId: Long): Boolean =
        db.queryOne("SELECT pgmq.archive(\$1, \$2)", listOf(queueName, msgId)) { it.get<Boolean>(0) } ?: false

    suspend fun archiveBatch(msgIds: List<Long>): List<Long> =
        db.query("SELECT * FROM pgmq.archive(\$1, \$2::bigint[])", listOf(queueName, msgIds)) { it.get<Long>(0) }

    /** Stops the polling loop and closes the dedicated connection. */
    suspend fun close() {
        scope.cancel()
        loopJob.join()
    }

    companion object {
        internal fun create(
            db: Cardio,
            queueName: String,
            visibilityTimeout: Int,
            batchSize: Int,
            maxPollSeconds: Int,
            pollIntervalMs: Int,
        ): PgmqConsumer = PgmqConsumer(
            db             = db,
            connectionConfig = db.connectionConfig(),
            registry       = db.codecRegistry(),
            queueName      = queueName,
            visibilityTimeout = visibilityTimeout,
            batchSize      = batchSize,
            maxPollSeconds = maxPollSeconds,
            pollIntervalMs = pollIntervalMs,
        )
    }
}
