package io.github.blad3mak3r.cardio.postgres

import io.github.blad3mak3r.cardio.postgres.CardioTransaction.Companion.EmptyArgs
import kotlinx.coroutines.currentCoroutineContext

open class CardioRepository<C : Cardio>(val db: C) {

    protected suspend fun <T> transaction(block: suspend CardioTransaction.() -> T): T {
        return db.inTransaction(block)
    }

    protected suspend fun tx(): CardioTransaction {
        return when (val ctx = currentCoroutineContext()[CardioTransaction.Context]?.tx) {
            null -> db.withConnection { connection ->
                CardioTransaction(connection)
            }
            else -> ctx
        }
    }

    protected suspend fun <T> query(
        stmt: String,
        args: List<Any?> = EmptyArgs,
        transform: (Row) -> T
    ): List<T> = tx().query(stmt, args, transform)

    protected suspend fun execute(
        stmt: String,
        args: List<Any?> = EmptyArgs
    ): Long = tx().execute(stmt, args)

}