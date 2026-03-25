import io.github.blad3mak3r.cardio.protocol.connection.Connection
import io.github.blad3mak3r.cardio.protocol.connection.ConnectionPool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CardioTests {

    companion object {
        var pool: ConnectionPool = ConnectionPool(
            configuration = ConnectionPool.Configuration(
                connect = Connection.Configuration(
                    host = "localhost",
                    port = 5432,
                    username = "test",
                    password = "test",
                    database = "test",
                )
            ))
    }

    @Test
    fun `01 - Initialize Pool`() {
        assert(pool != null) { "Connection pool should be initialized successfully." }
    }

    @Test
    fun `02- Execute query`(): Unit = runBlocking {
        val result = pool.query("SELECT 1") { row ->
            row.get<Int>(0)
        }.firstOrNull()

        assert(result == 1) { "Expected value to be 1, but got $result" }
    }
}