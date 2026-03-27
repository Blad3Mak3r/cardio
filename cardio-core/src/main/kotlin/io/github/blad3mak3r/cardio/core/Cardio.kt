package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import io.github.blad3mak3r.cardio.protocol.connection.ConnectionPool
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

open class Cardio(
    private val pool: ConnectionPool
) {

    class Configuration {

        var host: String            = "localhost"
        var port: Int               = 5432
        var database: String        = ""
        var username: String        = ""
        var password: String        = ""
        var ssl: Connection.SslMode = Connection.SslMode.DISABLE
        var applicationName: String = "cardio-pg-client"
        var maxSize: Int            = 10
        var minSize: Int            = 2
        var acquireTimeout: Duration = 30.seconds
        var idleTimeout: Duration    = 600.seconds

        private var registry: TypeCodecRegistry = TypeCodecRegistry.Default

        fun codecs(block: TypeCodecRegistry.() -> Unit) {
            registry = TypeCodecRegistry.Default.apply(block)
        }

        fun buildPoolConfig() = ConnectionPool.Configuration(
            connect = Connection.Configuration(
                host            = host,
                port            = port,
                database        = database.ifBlank { error("Cardio: database must not be blank") },
                username        = username.ifBlank { error("Cardio: username must not be blank") },
                password        = password,
                sslMode         = ssl,
                applicationName = applicationName,
            ),
            maxSize        = maxSize,
            minSize        = minSize,
            acquireTimeout = acquireTimeout,
            idleTimeout    = idleTimeout,
            registry       = registry,
        )
    }

    val stats: ConnectionPool.Stats
        get() = pool.stats

    suspend fun close() = pool.close()

    suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T
    ) = pool.query(sql = sql, params = params, mapper = mapper)

    suspend fun <T> queryOne(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T,
    ): T? = query(sql, *params, mapper = mapper).firstOrNull()

    suspend fun execute(
        sql: String,
        vararg params: Any?,
    ) = pool.execute(sql = sql, params = params)

    suspend fun <T> inTransaction(block: suspend (CardioTransaction) -> T): T =
        pool.transaction { conn -> block(CardioTransaction(conn)) }

    suspend fun useConnection(block: suspend (CardioTransaction) -> Unit) =
        pool.use { conn -> block(CardioTransaction(conn)) }

    companion object {
        suspend fun new(block: Configuration.() -> Unit): Cardio {
            val config = Configuration().apply(block)
            val pool   = ConnectionPool(config.buildPoolConfig())
            pool.probe()
            return Cardio(pool)
        }

        /**
         * Crea una subclase de [Cardio].
         * Requiere un constructor primario que acepte [ConnectionPool].
         *
         * Nota: este es el único punto de reflection en toda la librería.
         * Si prefieres zero-reflection absoluto, usa [create] y pasa la
         * instancia manualmente al constructor de tu subclase.
         */
        suspend inline fun <reified C : Cardio> newCustom(noinline block: Configuration.() -> Unit): C {
            val config = Configuration().apply(block)
            val pool   = ConnectionPool(config.buildPoolConfig())
            pool.probe()
            return C::class.java
                .getDeclaredConstructor(ConnectionPool::class.java)
                .newInstance(pool)
        }
    }
}