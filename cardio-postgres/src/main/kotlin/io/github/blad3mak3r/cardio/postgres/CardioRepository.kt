package io.github.blad3mak3r.cardio.postgres

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

open class CardioRepository<C : Cardio>(val db: C) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = db.coroutineContext

    protected suspend fun <T> transaction(block: suspend CardioTransaction.() -> T): Deferred<T> {
        return db.inTransaction(block)
    }

    protected fun <T> query(
        stmt: String,
        args: List<Any?> = emptyList(),
        transform: (Row) -> T
    ): Deferred<List<T>> = async {
        when (val ctx = currentCoroutineContext()[CardioTransaction.Context]?.tx) {
            null -> db.withConnection { connection ->
                val tx = CardioTransaction(connection)
                tx.query(stmt, args, transform)
            }.await()
            else -> ctx.query(stmt, args, transform)
        }
    }

    protected fun execute(stmt: String, args: List<Any?> = emptyList()): Deferred<Long> = async {
        when (val ctx = currentCoroutineContext()[CardioTransaction.Context]?.tx) {
            null -> db.withConnection { connection ->
                val tx = CardioTransaction(connection)
                tx.execute(stmt, args)
            }.await()
            else -> ctx.execute(stmt, args)
        }
    }

}