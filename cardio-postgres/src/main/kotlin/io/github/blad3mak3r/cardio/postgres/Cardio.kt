package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Connection
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.Logger
import org.slf4j.LoggerFactory

open class Cardio (internal val pool: ConnectionPool) {

    data class Configuration(
        var r2dbcConfig: PostgresqlConnectionConfiguration.Builder.() -> Unit = {},
        var poolConfig: ConnectionPoolConfiguration.Builder.() -> Unit = {}
    )

    companion object {
        val logger: Logger = LoggerFactory.getLogger(Cardio::class.java)

        suspend inline fun <reified T : Cardio> create(builder: Configuration.() -> Unit): T =
            create(Configuration().apply(builder))

        suspend inline fun <reified T : Cardio> create(configuration: Configuration): T {
            val r2dbcConfig = PostgresqlConnectionConfiguration.builder().apply(configuration.r2dbcConfig).build()
            val poolConfig = ConnectionPoolConfiguration.builder()
                .connectionFactory(PostgresqlConnectionFactory(r2dbcConfig))
                .apply(configuration.poolConfig)
                .build()
            val pool = ConnectionPool(poolConfig)

            val c = T::class.java.getDeclaredConstructor(ConnectionPool::class.java)
                .apply { isAccessible = true }
                .newInstance(pool)

            val version = c.inTransaction { tx ->
                tx.query(
                    stmt = """
                        SELECT version()
                    """.trimIndent()
                ) { row, _ ->
                    row.getAs<String>("version")
                }.first()
            }
            logger.info("Connected to Postgres version: $version")
            return c
        }
    }

    suspend fun <T> withConnection(block: suspend (conn: Connection) -> T): T {
        return pool.create().awaitSingle().use(block)
    }

    suspend fun <T> inTransaction(block: suspend (conn: CardioTransaction) -> T): T {
        return withConnection { conn ->
            conn.beginTransaction().awaitSingle()
            val transaction = CardioTransaction(conn)
            try {
                val result = block(transaction)
                conn.commitTransaction().awaitFirstOrNull()
                result
            } catch (e: Exception) {
                conn.rollbackTransaction().awaitFirstOrNull()
                logger.error("Transaction rolled back due to error", e)
                throw e
            }
        }
    }
}