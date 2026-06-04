import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.PgListener
import io.github.blad3mak3r.cardio.core.url
import io.github.blad3mak3r.cardio.protocol.PgNotification
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListenNotifyTests {

    companion object {
        private const val PG_URL = "postgres://test:test@localhost:5432/test?sslMode=disable&applicationName=test"
        private val listenerConfig: Cardio.Configuration.() -> Unit = { url(PG_URL) }

        // Time to allow the dedicated listen connection to connect and send LISTEN
        private const val LISTEN_READY_DELAY_MS = 1_000L

        var db: Cardio? = null
    }

    @BeforeAll
    fun setUp() {
        runBlocking { db = Cardio.new { url(PG_URL) } }
    }

    @AfterAll
    fun tearDown() {
        runBlocking { db?.close() }
    }

    @Test
    fun `basic notify and listen`() = runBlocking {
        val listener = PgListener.connect(listenerConfig)

        // Subscribe before listening so we don't miss events emitted right after LISTEN is set up
        val deferred = async {
            withTimeoutOrNull(5_000) { listener.notifications.first() }
        }

        listener.listen("basic_test")
        delay(LISTEN_READY_DELAY_MS)    // wait for LISTEN to reach the server
        db!!.notify("basic_test", "hello")

        val notif = deferred.await()
        listener.close()

        require(notif != null) { "Expected a notification but received none within timeout" }
        assert(notif.channel == "basic_test") { "channel mismatch: ${notif.channel}" }
        assert(notif.payload == "hello")      { "payload mismatch: ${notif.payload}" }
    }

    @Test
    fun `multiple channels`() = runBlocking {
        val listener = PgListener.connect(listenerConfig)

        val alphaDeferred = async {
            withTimeoutOrNull(5_000) { listener.channel("ch_alpha").first() }
        }
        val betaDeferred = async {
            withTimeoutOrNull(5_000) { listener.channel("ch_beta").first() }
        }

        listener.listen("ch_alpha", "ch_beta")
        delay(LISTEN_READY_DELAY_MS)
        db!!.notify("ch_alpha", "alpha-payload")
        db!!.notify("ch_beta",  "beta-payload")

        val alpha = alphaDeferred.await()
        val beta  = betaDeferred.await()
        listener.close()

        require(alpha != null) { "Expected notification on ch_alpha" }
        require(beta  != null) { "Expected notification on ch_beta" }
        assert(alpha.payload == "alpha-payload") { "alpha payload: ${alpha.payload}" }
        assert(beta.payload  == "beta-payload")  { "beta payload: ${beta.payload}" }
    }

    @Test
    fun `notify with empty payload`() = runBlocking {
        val listener = PgListener.connect(listenerConfig)

        val deferred = async {
            withTimeoutOrNull(5_000) { listener.notifications.first() }
        }

        listener.listen("empty_payload_test")
        delay(LISTEN_READY_DELAY_MS)
        db!!.notify("empty_payload_test")       // no payload argument

        val notif = deferred.await()
        listener.close()

        require(notif != null) { "Expected a notification but received none" }
        assert(notif.payload == "") { "Expected empty payload, got: '${notif.payload}'" }
    }

    @Test
    fun `multiple collectors receive same notification`() = runBlocking {
        val listener = PgListener.connect(listenerConfig)

        val received1 = mutableListOf<PgNotification>()
        val received2 = mutableListOf<PgNotification>()

        val job1 = launch {
            withTimeoutOrNull(5_000) {
                listener.notifications.collect { received1 += it }
            }
        }
        val job2 = launch {
            withTimeoutOrNull(5_000) {
                listener.notifications.collect { received2 += it }
            }
        }

        listener.listen("multi_collector_test")
        delay(LISTEN_READY_DELAY_MS)
        db!!.notify("multi_collector_test", "shared")

        delay(500)      // give collectors time to receive
        job1.cancel()
        job2.cancel()
        listener.close()

        assert(received1.any { it.payload == "shared" }) { "Collector 1 did not receive the notification" }
        assert(received2.any { it.payload == "shared" }) { "Collector 2 did not receive the notification" }
    }

    @Test
    fun `unlisten stops receiving notifications`() = runBlocking {
        val listener = PgListener.connect(listenerConfig)

        // Confirm initial delivery works
        val firstDeferred = async {
            withTimeoutOrNull(5_000) { listener.notifications.first() }
        }

        listener.listen("unlisten_test")
        delay(LISTEN_READY_DELAY_MS)
        db!!.notify("unlisten_test", "before-unlisten")

        val firstNotif = firstDeferred.await()
        require(firstNotif != null) { "Expected first notification before unlisten" }

        // Unlisten; no further notifications should arrive
        listener.unlisten("unlisten_test")
        delay(LISTEN_READY_DELAY_MS)    // wait for reconnect / UNLISTEN to propagate

        val afterDeferred = async {
            withTimeoutOrNull(2_000) { listener.notifications.first() }
        }
        db!!.notify("unlisten_test", "after-unlisten")
        val afterNotif = afterDeferred.await()
        listener.close()

        assert(afterNotif == null) {
            "Expected no notification after unlisten, but received: ${afterNotif?.payload}"
        }
    }

    @Test
    fun `notify inside transaction is delivered after commit`() = runBlocking {
        val listener = PgListener.connect(listenerConfig)

        val deferred = async {
            withTimeoutOrNull(5_000) { listener.notifications.first() }
        }

        listener.listen("tx_notify_test")
        delay(LISTEN_READY_DELAY_MS)

        db!!.inTransaction {
            db!!.notify("tx_notify_test", "from-tx")
        }

        val notif = deferred.await()
        listener.close()

        require(notif != null) { "Expected notification after transaction commit" }
        assert(notif.channel == "tx_notify_test") { "channel: ${notif.channel}" }
        assert(notif.payload == "from-tx")         { "payload: ${notif.payload}" }
    }
}
