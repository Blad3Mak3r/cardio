package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.spi.Connection
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import reactor.core.publisher.Flux
import kotlin.coroutines.CoroutineContext

class CardioTransaction(val c: Connection) : AutoCloseable {

    data class Context(val tx: CardioTransaction) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<Context>

        override val key: CoroutineContext.Key<*>
            get() = Key
    }

    override fun close() = runBlocking<Unit> {
        c.close().awaitSingle()
    }

    suspend fun <T> query(
        stmt: String,
        args: List<Any?> = emptyList(),
        transform: (Row, RowMetadata) -> T
    ): List<T> {
        return try {
            val statement = c.createStatement(stmt)

            args.forEachIndexed { i, v ->
                when(v) {
                    null -> statement.bindNull(i, Any::class.java)
                    else -> statement.bind(i, v)
                }
            }

            Flux.from(statement.execute())
                .flatMap { r ->
                    r.map { r, m ->
                        transform(r, m)
                    }
                }
                .collectList()
                .awaitSingle()
        } catch (e: Exception) {
            // Don't wrap CardioExceptions
            if (e is CardioException) {
                throw e
            }
            throw CardioQueryException(stmt, args, e)
        }
    }

    suspend fun execute(
        stmt: String,
        args: List<Any?> = emptyList()
    ): Long {
        return try {
            val statement = c.createStatement(stmt)

            args.forEachIndexed { i, v ->
                when(v) {
                    null -> statement.bindNull(i, Any::class.java)
                    else -> statement.bind(i, v)
                }
            }

            Flux.from(statement.execute())
                .flatMap { result -> result.rowsUpdated }
                .reduce(0L) { acc, value -> acc + value }
                .awaitSingle()
        } catch (e: Exception) {
            throw CardioExecutionException(stmt, args, e)
        }
    }
}