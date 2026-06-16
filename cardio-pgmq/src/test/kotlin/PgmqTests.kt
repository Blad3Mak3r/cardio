import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.url
import io.github.blad3mak3r.cardio.pgmq.pgmq
import io.github.blad3mak3r.cardio.pgmq.pgmqConsumer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgmqTests {

    companion object {
        var client: Cardio? = null
        const val QUEUE = "test_cardio_pgmq"
    }

    @BeforeAll
    fun setUp() = runBlocking {
        client = Cardio.new {
            url("postgres://test:test@localhost:5432/test?sslMode=disable&applicationName=pgmq-test")
            minSize = 1
            maxSize = 5
        }
        client!!.execute("CREATE EXTENSION IF NOT EXISTS pgmq")
        // Ensure a clean state
        runCatching { client!!.pgmq().dropQueue(QUEUE) }
    }

    @AfterAll
    fun tearDown() = runBlocking {
        runCatching { client!!.pgmq().dropQueue(QUEUE) }
        client?.close()
    }

    @Test
    fun createAndListQueue() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)
        val queues = pgmq.listQueues()
        assert(queues.any { it.queueName == QUEUE }) { "Expected $QUEUE in queue list" }
    }

    @Test
    fun dropQueueReturnValues() = runBlocking {
        val pgmq = client!!.pgmq()
        val tmpQueue = "${QUEUE}_drop"
        pgmq.createQueue(tmpQueue)
        assert(pgmq.dropQueue(tmpQueue)) { "dropQueue should return true for an existing queue" }
        assert(!pgmq.dropQueue(tmpQueue)) { "dropQueue should return false for a non-existent queue" }
    }

    @Test
    fun sendReadDeleteRoundTrip() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        val payload = """{"order":"123"}"""
        val msgId = pgmq.send(QUEUE, payload)
        assert(msgId > 0) { "send should return a positive msgId" }

        val messages = pgmq.read(QUEUE, visibilityTimeout = 30, qty = 1)
        assert(messages.size == 1) { "Expected 1 message, got ${messages.size}" }
        assert(messages[0].msgId == msgId)
        assert(messages[0].message == payload)

        assert(pgmq.delete(QUEUE, msgId)) { "delete should return true" }
        val afterDelete = pgmq.read(QUEUE, visibilityTimeout = 30, qty = 1)
        assert(afterDelete.isEmpty()) { "Queue should be empty after delete" }
    }

    @Test
    fun sendBatchReadDeleteBatch() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        val payloads = listOf("""{"n":1}""", """{"n":2}""", """{"n":3}""")
        val msgIds = pgmq.sendBatch(QUEUE, payloads)
        assert(msgIds.size == 3) { "Expected 3 msgIds, got ${msgIds.size}" }

        val messages = pgmq.read(QUEUE, visibilityTimeout = 30, qty = 3)
        assert(messages.size == 3) { "Expected 3 messages" }

        val deleted = pgmq.deleteBatch(QUEUE, msgIds)
        assert(deleted.size == 3) { "Expected 3 deleted msgIds" }
        assert(deleted.containsAll(msgIds))
    }

    @Test
    fun popAtomicReadDelete() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        pgmq.send(QUEUE, """{"pop":"test"}""")
        val popped = pgmq.pop(QUEUE, qty = 1)
        assert(popped.size == 1) { "Expected 1 popped message" }

        // Message should be gone — pop is atomic read+delete
        val remaining = pgmq.read(QUEUE, visibilityTimeout = 5, qty = 1)
        assert(remaining.isEmpty()) { "Queue should be empty after pop" }
    }

    @Test
    fun archiveMessage() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        val msgId = pgmq.send(QUEUE, """{"archive":"test"}""")
        assert(pgmq.archive(QUEUE, msgId)) { "archive should return true" }

        val remaining = pgmq.read(QUEUE, visibilityTimeout = 5, qty = 1)
        assert(remaining.isEmpty()) { "Queue should be empty after archive" }
    }

    @Test
    fun archiveBatch() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        val ids = listOf(
            pgmq.send(QUEUE, """{"a":1}"""),
            pgmq.send(QUEUE, """{"a":2}"""),
        )
        val archived = pgmq.archiveBatch(QUEUE, ids)
        assert(archived.size == 2) { "Expected 2 archived msgIds" }
        assert(archived.containsAll(ids))
    }

    @Test
    fun metricsReflectsQueueLength() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)
        pgmq.purgeQueue(QUEUE)

        pgmq.send(QUEUE, """{"m":1}""")
        pgmq.send(QUEUE, """{"m":2}""")

        val m = pgmq.metrics(QUEUE)
        assert(m != null) { "metrics should not be null" }
        assert(m!!.queueLength >= 2) { "queueLength should be at least 2, got ${m.queueLength}" }

        val all = pgmq.metricsAll()
        assert(all.any { it.queueName == QUEUE }) { "metricsAll should include $QUEUE" }

        pgmq.purgeQueue(QUEUE)
    }

    @Test
    fun readWithPollReturnsMessages() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        pgmq.send(QUEUE, """{"poll":"test"}""")
        val messages = pgmq.readWithPoll(QUEUE, visibilityTimeout = 30, qty = 1, maxPollSeconds = 3)
        assert(messages.isNotEmpty()) { "readWithPoll should return at least one message" }
        pgmq.deleteBatch(QUEUE, messages.map { it.msgId })
    }

    @Test
    fun setVtExtendsVisibility() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        val msgId = pgmq.send(QUEUE, """{"vt":"test"}""")
        // Read it to put it in-flight
        pgmq.read(QUEUE, visibilityTimeout = 1, qty = 1)
        // Extend VT to 60s
        val updated = pgmq.setVt(QUEUE, msgId, vtSeconds = 60)
        assert(updated != null) { "setVt should return the updated message" }
        assert(updated!!.msgId == msgId)

        pgmq.delete(QUEUE, msgId)
    }

    @Test
    fun purgeQueueReturnsCount() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        pgmq.send(QUEUE, """{"purge":1}""")
        pgmq.send(QUEUE, """{"purge":2}""")
        val purged = pgmq.purgeQueue(QUEUE)
        assert(purged >= 2) { "purgeQueue should return at least 2, got $purged" }
    }

    @Test
    fun consumerReceivesAndAcks() = runBlocking {
        val pgmq = client!!.pgmq()
        pgmq.createQueue(QUEUE)

        val consumer = client!!.pgmqConsumer(QUEUE, visibilityTimeout = 30, maxPollSeconds = 5)
        try {
            val payload = """{"consumer":"test"}"""
            pgmq.send(QUEUE, payload)

            val received = withTimeout(15.seconds) {
                consumer.messages.first()
            }
            assert(received.message == payload) { "Unexpected message: ${received.message}" }
            consumer.ack(received.msgId)

            val remaining = pgmq.read(QUEUE, visibilityTimeout = 5, qty = 1)
            assert(remaining.isEmpty()) { "Queue should be empty after ack" }
        } finally {
            consumer.close()
        }
    }
}
