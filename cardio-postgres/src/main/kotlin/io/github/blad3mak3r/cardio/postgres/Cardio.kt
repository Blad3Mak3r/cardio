package io.github.blad3mak3r.cardio.postgres

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

open class Cardio(internal val pool: Pool) : CoroutineScope by CardioScope {

    data class Configuration(
        var connectOptions: PgConnectOptions = PgConnectOptions(),
        var poolOptions: PoolOptions = PoolOptions()
    )

    companion object {
        val logger: Logger = LoggerFactory.getLogger(Cardio::class.java)

        suspend inline fun <reified T : Cardio> create(builder: Configuration.() -> Unit): T =
            create(Configuration().apply(builder))

        suspend inline fun <reified T : Cardio> create(configuration: Configuration): T {
            val pool: Pool = PgBuilder.pool()
                .connectingTo(configuration.connectOptions)
                .with(configuration.poolOptions)
                .build()

            val c = T::class.java.getDeclaredConstructor(Pool::class.java)
                .apply { isAccessible = true }
                .newInstance(pool)

            val version = c.inTransaction {
                query(stmt = "SELECT version()") { row ->
                    row.getString("version")
                }.first()
            }.await()
            logger.info("Connected to Postgres version: $version")
            return c
        }
    }

    fun <T> withConnection(block: suspend (conn: SqlConnection) -> T): Deferred<T> = async {
        val conn = pool.connection.coAwait()
        conn.use(block)
    }

    suspend fun <T> inTransaction(block: suspend CardioTransaction.() -> T): Deferred<T> {
        return withConnection { conn ->
            val tx = conn.begin().coAwait()
            val cardioTx = CardioTransaction(conn)
            try {
                val result = withContext(CardioTransaction.Context(cardioTx)) {
                    cardioTx.block()
                }
                tx.commit().coAwait()
                result
            } catch (e: Exception) {
                tx.rollback().coAwait()
                logger.error("Transaction rolled back due to error", e)
                throw e
            }
        }
    }
}