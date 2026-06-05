package io.github.blad3mak3r.cardio.pgmq

import io.github.blad3mak3r.cardio.core.Cardio

class PgmqClient(private val db: Cardio) {

    // ── Queue management ──────────────────────────────────────────────────────

    suspend fun createQueue(name: String) =
        db.execute("SELECT pgmq.create(\$1)", listOf(name))

    suspend fun createUnloggedQueue(name: String) =
        db.execute("SELECT pgmq.create_unlogged(\$1)", listOf(name))

    suspend fun dropQueue(name: String): Boolean =
        db.queryOne("SELECT pgmq.drop_queue(\$1)", listOf(name)) { it.get<Boolean>(0) } ?: false

    suspend fun listQueues(): List<QueueInfo> =
        db.query("SELECT * FROM pgmq.list_queues()") { it.toQueueInfo() }

    suspend fun purgeQueue(name: String): Long =
        db.queryOne("SELECT pgmq.purge_queue(\$1)", listOf(name)) { it.get<Long>(0) } ?: 0L

    // ── Metrics ───────────────────────────────────────────────────────────────

    suspend fun metrics(queueName: String): QueueMetrics? =
        db.queryOne("SELECT * FROM pgmq.metrics(\$1)", listOf(queueName)) { it.toQueueMetrics() }

    suspend fun metricsAll(): List<QueueMetrics> =
        db.query("SELECT * FROM pgmq.metrics_all()") { it.toQueueMetrics() }

    // ── Sending ───────────────────────────────────────────────────────────────

    suspend fun send(queueName: String, message: String, delaySeconds: Int = 0): Long =
        db.queryOne("SELECT pgmq.send(\$1, \$2::jsonb, \$3)", listOf(queueName, message, delaySeconds)) { it.get<Long>(0) }!!

    suspend fun sendBatch(queueName: String, messages: List<String>, delaySeconds: Int = 0): List<Long> =
        db.query("SELECT * FROM pgmq.send_batch(\$1, \$2::jsonb[], \$3)", listOf(queueName, messages, delaySeconds)) { it.get<Long>(0) }

    // ── Reading ───────────────────────────────────────────────────────────────

    suspend fun read(queueName: String, visibilityTimeout: Int, qty: Int = 1): List<PgmqMessage> =
        db.query("SELECT * FROM pgmq.read(\$1, \$2, \$3)", listOf(queueName, visibilityTimeout, qty)) { it.toPgmqMessage() }

    suspend fun readWithPoll(
        queueName: String,
        visibilityTimeout: Int,
        qty: Int = 1,
        maxPollSeconds: Int = 5,
        pollIntervalMs: Int = 100,
    ): List<PgmqMessage> =
        db.query(
            "SELECT * FROM pgmq.read_with_poll(\$1, \$2, \$3, \$4, \$5)",
            listOf(queueName, visibilityTimeout, qty, maxPollSeconds, pollIntervalMs),
        ) { it.toPgmqMessage() }

    suspend fun pop(queueName: String, qty: Int = 1): List<PgmqMessage> =
        db.query("SELECT * FROM pgmq.pop(\$1, \$2)", listOf(queueName, qty)) { it.toPgmqMessage() }

    // ── Delete / Archive ──────────────────────────────────────────────────────

    suspend fun delete(queueName: String, msgId: Long): Boolean =
        db.queryOne("SELECT pgmq.delete(\$1, \$2)", listOf(queueName, msgId)) { it.get<Boolean>(0) } ?: false

    suspend fun deleteBatch(queueName: String, msgIds: List<Long>): List<Long> =
        db.query("SELECT * FROM pgmq.delete(\$1, \$2::bigint[])", listOf(queueName, msgIds)) { it.get<Long>(0) }

    suspend fun archive(queueName: String, msgId: Long): Boolean =
        db.queryOne("SELECT pgmq.archive(\$1, \$2)", listOf(queueName, msgId)) { it.get<Boolean>(0) } ?: false

    suspend fun archiveBatch(queueName: String, msgIds: List<Long>): List<Long> =
        db.query("SELECT * FROM pgmq.archive(\$1, \$2::bigint[])", listOf(queueName, msgIds)) { it.get<Long>(0) }

    // ── Visibility timeout ────────────────────────────────────────────────────

    suspend fun setVt(queueName: String, msgId: Long, vtSeconds: Int): PgmqMessage? =
        db.queryOne("SELECT * FROM pgmq.set_vt(\$1, \$2, \$3)", listOf(queueName, msgId, vtSeconds)) { it.toPgmqMessage() }

    suspend fun setVtBatch(queueName: String, msgIds: List<Long>, vtSeconds: Int): List<PgmqMessage> =
        db.query("SELECT * FROM pgmq.set_vt(\$1, \$2::bigint[], \$3)", listOf(queueName, msgIds, vtSeconds)) { it.toPgmqMessage() }
}
