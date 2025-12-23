package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.Connection
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingle
import kotlin.io.use

class Cardio(internal val pool: ConnectionPool) {

    data class Configuration(
        val r2dbcConfig: PostgresqlConnectionConfiguration.Builder.() -> Unit = {},
        val poolConfig: ConnectionPoolConfiguration.Builder.() -> Unit = {}
    )

    companion object {
        suspend fun create(configuration: Configuration): Cardio {
            val r2dbcConfig = PostgresqlConnectionConfiguration.builder().apply(configuration.r2dbcConfig).build()
            val poolConfig = ConnectionPoolConfiguration.builder()
                .connectionFactory(PostgresqlConnectionFactory(r2dbcConfig))
                .apply(configuration.poolConfig)
                .build()
            val c = Cardio(ConnectionPool(poolConfig))

            val v = c.withConnection { conn ->
                conn.createStatement(
                    """
                    SELECT version() AS version
                    """.trimIndent()
                ).execute().awaitSingle().map {
                    it.get("version") as String
                }
            }
            return c
        }
    }

    suspend fun <T> withConnection(block: suspend (conn: Connection) -> T): T {
        val conn = pool.create().awaitSingle()
        try {
            return block(conn)
        } finally {
            conn.close().awaitSingle()
        }
    }

    suspend fun <T> inTransaction(block: suspend (conn: Connection) -> T): T {
        return withConnection { conn ->
            conn.beginTransaction().awaitSingle()
            try {
                val result = block(conn)
                conn.commitTransaction().awaitSingle()
                result
            } catch (e: Exception) {
                conn.rollbackTransaction().awaitSingle()
                throw e
            }
        }
    }
}