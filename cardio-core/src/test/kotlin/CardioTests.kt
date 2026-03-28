import io.github.blad3mak3r.cardio.core.Cardio
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardioTests {

    companion object {
        var client: Cardio? = null
    }

    @BeforeAll
    fun setUp() = runBlocking {
        client = Cardio.new {
            host = "localhost"
            port = 5432
            username = "test"
            password = "test"
            database = "test"
            minSize = 1
            maxSize = 1
        }
    }

    @Test
    fun checkClient() = runBlocking {
        require(client != null) { "Connection pool should be initialized successfully." }
        assert(client!!.stats.totalConnections == 1) { "Connection pool should be initialized successfully." }
    }

    @Test
    fun intTests(): Unit = runBlocking {
        val result = client!!.query("SELECT 1") { row ->
            row.get<Int>(0)
        }.firstOrNull()

        assert(result == 1) { "Expected value to be 1, but got $result" }
    }


    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun kotlinUuidTest() = runBlocking {
        val uuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val result = client!!.query("SELECT $1::uuid", uuid) { row ->
            row.get<Uuid>(0)
        }.firstOrNull()

        assert(result == uuid) { "Expected value to be UUID, but got $result" }
    }

    @Test
    fun byteArrayTest() = runBlocking {
        val byteArray = ByteArray(256) { it.toByte() }
        val result = client!!.query("SELECT $1::bytea", byteArray) { row ->
            row.get<ByteArray>(0)
        }.firstOrNull()

        assert(result.contentEquals(byteArray)) { "Expected value to be byteArray, but got $result" }
    }

    @Test
    fun intArrayTest() = runBlocking {
        val intArray = IntArray(256) { it }
        val result = client!!.query("SELECT unnest($1::int[])", intArray) { row ->
            row.get<Int>(0)
        }
        
        assert(result.size == intArray.size) { "Expected result size to be ${intArray.size}, but got ${result.size}" }
        for (i in intArray.indices) {
            assert(result[i] == intArray[i]) { "Expected value at index $i to be ${intArray[i]}, but got ${result[i]}" }
        }
    }

    @Test
    fun textArrayTest() = runBlocking {
        val textArray = Array(256) { it.toString() }
        val result = client!!.query("SELECT unnest($1::text[])", textArray) { row ->
            row.get<String>(0)
        }

        assert(result.size == textArray.size) { "Expected result size to be ${textArray.size}, but got ${result.size}" }
        for (i in textArray.indices) {
            assert(result[i] == textArray[i]) { "Expected value at index $i to be ${textArray[i]}, but got ${result[i]}" }
        }
    }
}