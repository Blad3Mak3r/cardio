package io.github.blad3mak3r.cardio.postgres

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlin.coroutines.CoroutineContext

class CardioTransaction(val c: SqlConnection) {

    companion object {
        private fun List<Any?>.toTuple(): Tuple = Tuple.tuple(this)
        internal val EmptyArgs = emptyList<Any?>()
    }

    data class Context(val tx: CardioTransaction) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<Context>

        override val key: CoroutineContext.Key<*>
            get() = Key
    }

    suspend fun <T> query(
        stmt: String,
        args: List<Any?> = EmptyArgs,
        transform: (Row) -> T
    ): List<T> {
        val result = if (args.isEmpty()) {
            c.query(stmt).execute().coAwait()
        } else {
            c.preparedQuery(stmt).execute(args.toTuple()).coAwait()
        }
        return result.map { transform(it) }
    }

    suspend fun execute(
        stmt: String,
        args: List<Any?> = EmptyArgs
    ): Long {
        val result = if (args.isEmpty()) {
            c.query(stmt).execute().coAwait()
        } else {
            c.preparedQuery(stmt).execute(args.toTuple()).coAwait()
        }
        return result.rowCount().toLong()
    }
}