import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.url
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import io.github.blad3mak3r.cardio.protocol.connection.PgConnectException
import io.github.blad3mak3r.cardio.protocol.connection.PgSslException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for TLS/SSL support.
 *
 * Connection-level tests that require an actual TLS-enabled PostgreSQL are skipped
 * automatically when the server reports that SSL is not available (PREFER fall-back path).
 * Those tests check only the REQUIRE / VERIFY_CA / VERIFY_FULL enforcement logic —
 * i.e., that the library throws the correct exception type when TLS is mandatory but
 * the server doesn't offer it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SslTests {

    // ───────────────────────────────────────────────────────────────────
    // URL parsing
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `url parser - sslMode disable`() {
        val cfg = Cardio.Configuration().apply {
            url("postgres://test:test@localhost:5432/test?sslMode=disable")
        }
        assertEquals(Connection.SslMode.DISABLE, cfg.ssl)
    }

    @Test
    fun `url parser - sslMode prefer`() {
        val cfg = Cardio.Configuration().apply {
            url("postgres://test:test@localhost:5432/test?sslMode=prefer")
        }
        assertEquals(Connection.SslMode.PREFER, cfg.ssl)
    }

    @Test
    fun `url parser - sslMode require`() {
        val cfg = Cardio.Configuration().apply {
            url("postgres://test:test@localhost:5432/test?sslMode=require")
        }
        assertEquals(Connection.SslMode.REQUIRE, cfg.ssl)
    }

    @Test
    fun `url parser - sslMode verify-ca`() {
        val cfg = Cardio.Configuration().apply {
            url("postgres://test:test@localhost:5432/test?sslMode=verify-ca")
        }
        assertEquals(Connection.SslMode.VERIFY_CA, cfg.ssl)
    }

    @Test
    fun `url parser - sslMode verify-full`() {
        val cfg = Cardio.Configuration().apply {
            url("postgres://test:test@localhost:5432/test?sslMode=verify-full")
        }
        assertEquals(Connection.SslMode.VERIFY_FULL, cfg.ssl)
    }

    @Test
    fun `url parser - missing sslMode defaults to DISABLE`() {
        val cfg = Cardio.Configuration().apply {
            url("postgres://test:test@localhost:5432/test")
        }
        assertEquals(Connection.SslMode.DISABLE, cfg.ssl)
    }

    @Test
    fun `url parser - invalid sslMode throws`() {
        val ex = assertThrows<IllegalStateException> {
            Cardio.Configuration().apply {
                url("postgres://test:test@localhost:5432/test?sslMode=invalid")
            }
        }
        assert(ex.message?.contains("invalid") == true)
    }

    // ───────────────────────────────────────────────────────────────────
    // Configuration equality with sslRootCert (ByteArray)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `Configuration equals is content-based for sslRootCert`() {
        val cert = byteArrayOf(1, 2, 3)
        val a = Connection.Configuration(
            database = "test", username = "test", password = "test",
            sslRootCert = cert.copyOf()
        )
        val b = Connection.Configuration(
            database = "test", username = "test", password = "test",
            sslRootCert = cert.copyOf()
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Configuration with null sslRootCert equals another null`() {
        val a = Connection.Configuration(database = "test", username = "test", password = "test")
        val b = Connection.Configuration(database = "test", username = "test", password = "test")
        assertEquals(a, b)
    }

    // ───────────────────────────────────────────────────────────────────
    // SslMode enum coverage
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `SslMode enum contains all five modes`() {
        val modes = Connection.SslMode.entries.map { it.name }.toSet()
        assert("DISABLE"     in modes) { "DISABLE missing" }
        assert("PREFER"      in modes) { "PREFER missing" }
        assert("REQUIRE"     in modes) { "REQUIRE missing" }
        assert("VERIFY_CA"   in modes) { "VERIFY_CA missing" }
        assert("VERIFY_FULL" in modes) { "VERIFY_FULL missing" }
    }

    // ───────────────────────────────────────────────────────────────────
    // DISABLE — plain connection (mirrors setUp in CardioTests)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `DISABLE mode connects and queries`() = runBlocking {
        val client = Cardio.new {
            host = "localhost"; port = 5432
            database = "test"; username = "test"; password = "test"
            ssl = Connection.SslMode.DISABLE
            minSize = 1; maxSize = 1
        }
        try {
            val result = client.query("SELECT 1") { it.get<Int>(0) }
            assertEquals(listOf(1), result)
        } finally {
            client.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // PREFER — must succeed regardless of whether server supports SSL
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `PREFER mode connects and queries (falls back to plain if no SSL)`() = runBlocking {
        val client = Cardio.new {
            host = "localhost"; port = 5432
            database = "test"; username = "test"; password = "test"
            ssl = Connection.SslMode.PREFER
            minSize = 1; maxSize = 1
        }
        try {
            val result = client.query("SELECT 42") { it.get<Int>(0) }
            assertEquals(listOf(42), result)
        } finally {
            client.close()
        }
    }

    @Test
    fun `PREFER mode URL string connects`() = runBlocking {
        val client = Cardio.new {
            url("postgres://test:test@localhost:5432/test?sslMode=prefer")
            minSize = 1; maxSize = 1
        }
        try {
            val result = client.query("SELECT 'ssl-prefer'") { it.get<String>(0) }
            assertEquals(listOf("ssl-prefer"), result)
        } finally {
            client.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // REQUIRE — must throw when the server has no SSL
    //
    // The test PostgreSQL instance at localhost:5432 does NOT have SSL
    // enabled (matching the CI configuration).  REQUIRE must therefore
    // surface either a PgSslException (server said 'N') or a
    // PgConnectException wrapping it.
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `REQUIRE mode throws when server has no SSL`() {
        val ex = assertThrows<Exception> {
            runBlocking {
                Cardio.new {
                    host = "localhost"; port = 5432
                    database = "test"; username = "test"; password = "test"
                    ssl = Connection.SslMode.REQUIRE
                    minSize = 1; maxSize = 1
                }
            }
        }
        // The root cause must be a PgSslException
        val root = generateSequence<Throwable>(ex) { it.cause }
        assert(root.any { it is PgSslException }) {
            "Expected root cause to be PgSslException, got: ${ex::class.simpleName} — ${ex.message}"
        }
    }

    @Test
    fun `VERIFY_CA mode throws when server has no SSL`() {
        val ex = assertThrows<Exception> {
            runBlocking {
                Cardio.new {
                    host = "localhost"; port = 5432
                    database = "test"; username = "test"; password = "test"
                    ssl = Connection.SslMode.VERIFY_CA
                    minSize = 1; maxSize = 1
                }
            }
        }
        val root = generateSequence<Throwable>(ex) { it.cause }
        assert(root.any { it is PgSslException }) {
            "Expected root cause to be PgSslException, got: ${ex::class.simpleName} — ${ex.message}"
        }
    }

    @Test
    fun `VERIFY_FULL mode throws when server has no SSL`() {
        val ex = assertThrows<Exception> {
            runBlocking {
                Cardio.new {
                    host = "localhost"; port = 5432
                    database = "test"; username = "test"; password = "test"
                    ssl = Connection.SslMode.VERIFY_FULL
                    minSize = 1; maxSize = 1
                }
            }
        }
        val root = generateSequence<Throwable>(ex) { it.cause }
        assert(root.any { it is PgSslException }) {
            "Expected root cause to be PgSslException, got: ${ex::class.simpleName} — ${ex.message}"
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Cardio.Configuration.sslRootCert wiring
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `sslRootCert is correctly wired into Connection Configuration`() {
        val cert = byteArrayOf(10, 20, 30)
        val cfg = Cardio.Configuration().apply {
            host = "localhost"; port = 5432
            database = "test"; username = "test"; password = "test"
            ssl = Connection.SslMode.VERIFY_CA
            sslRootCert = cert
        }
        val poolCfg = cfg.buildPoolConfig()
        assertNotNull(poolCfg.connect.sslRootCert)
        assert(poolCfg.connect.sslRootCert!!.contentEquals(cert))
        assertEquals(Connection.SslMode.VERIFY_CA, poolCfg.connect.sslMode)
    }

    @Test
    fun `sslRootCert null is preserved in Connection Configuration`() {
        val cfg = Cardio.Configuration().apply {
            host = "localhost"; port = 5432
            database = "test"; username = "test"; password = "test"
            ssl = Connection.SslMode.VERIFY_CA
            sslRootCert = null
        }
        val poolCfg = cfg.buildPoolConfig()
        assertNull(poolCfg.connect.sslRootCert)
    }

    // ───────────────────────────────────────────────────────────────────
    // Concurrency — multiple simultaneous connections via PREFER
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `concurrent PREFER connections produce correct results`() = runBlocking {
        val client = Cardio.new {
            host = "localhost"; port = 5432
            database = "test"; username = "test"; password = "test"
            ssl = Connection.SslMode.PREFER
            minSize = 2; maxSize = 8
        }
        try {
            val deferred = (1..8).map { n ->
                async { client.query("SELECT $n") { it.get<Int>(0) } }
            }
            val results = deferred.awaitAll()
            results.forEachIndexed { index, result ->
                assertEquals(listOf(index + 1), result)
            }
        } finally {
            client.close()
        }
    }
}
