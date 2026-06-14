import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.url
import io.github.blad3mak3r.cardio.protocol.codec.JsonbCodec
import io.github.blad3mak3r.cardio.protocol.codec.Param
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
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
            url("postgres://test:test@localhost:5432/test?sslMode=disable&applicationName=test")
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
    fun intTests() = runBlocking {
        val result = client!!.query("SELECT 1") { row ->
            row.get<Int>(0)
        }.firstOrNull()

        assert(result == 1) { "Expected value to be 1, but got $result" }
    }


    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun kotlinUuidTest() = runBlocking {
        val uuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val result = client!!.query("SELECT $1::uuid", listOf(uuid)) { row ->
            row.get<Uuid>(0)
        }.firstOrNull()

        assert(result == uuid) { "Expected value to be UUID, but got $result" }
    }

    @Test
    fun byteArrayTest() = runBlocking {
        val byteArray = ByteArray(256) { it.toByte() }
        val result = client!!.query("SELECT $1::bytea", listOf(byteArray)) { row ->
            row.get<ByteArray>(0)
        }.firstOrNull()

        assert(result.contentEquals(byteArray)) { "Expected value to be byteArray, but got $result" }
    }

    @Test
    fun intArrayTest() = runBlocking {
        val intArray = IntArray(256) { it }
        val result = client!!.query("SELECT unnest($1::int[])", listOf(intArray)) { row ->
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
        val result = client!!.query("SELECT unnest($1::text[])", listOf(textArray)) { row ->
            row.get<String>(0)
        }

        assert(result.size == textArray.size) { "Expected result size to be ${textArray.size}, but got ${result.size}" }
        for (i in textArray.indices) {
            assert(result[i] == textArray[i]) { "Expected value at index $i to be ${textArray[i]}, but got ${result[i]}" }
        }
    }

    @Test
    fun boolArrayTest() = runBlocking {
        val boolArray = BooleanArray(256) { it % 2 == 0 }
        val result = client!!.query("SELECT unnest($1::bool[])", listOf(boolArray)) { row ->
            row.get<Boolean>(0)
        }

        assert(result.size == boolArray.size) { "Expected result size to be ${boolArray.size}, but got ${result.size}" }
        for (i in boolArray.indices) {
            assert(result[i] == boolArray[i]) { "Expected value at index $i to be ${boolArray[i]}, but got ${result[i]}" }
        }
    }

    @Test
    fun jsonbRoundTripTest() = runBlocking {
        val json = "{\"name\":\"caf\\u00e9\",\"emoji\":\"🎉\",\"n\":42}"
        val result = client!!.query("SELECT $1::jsonb", listOf(Param(json, JsonbCodec))) { row ->
            row.get<String>(0)
        }.first()

        val expected = client!!.query("SELECT $1::jsonb::text", listOf(Param(json, JsonbCodec))) { row ->
            row.get<String>(0)
        }.first()

        assert(result == expected) { "Expected '$expected', but got '$result'" }
    }

    @Test
    fun unicodeColumnNameTest() = runBlocking {
        val result = client!!.query("SELECT 1 AS \"ñoño_emoji_🎉\"") { row ->
            row.columnNames.first() to row.get<Int>("ñoño_emoji_🎉")
        }.first()

        assert(result.first == "ñoño_emoji_🎉") { "Expected UTF-8 column name to round-trip, but got '${result.first}'" }
        assert(result.second == 1) { "Expected value to be 1, but got ${result.second}" }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            require(client != null) { "Connection pool should be initialized successfully." }
            val stats = client!!.stats
            assert(stats.totalAcquired == stats.totalReleased) {
                "Expected all acquired connections to be released, but acquired=${stats.totalAcquired} released=${stats.totalReleased}"
            }
            client?.close()
        }
    }
}
