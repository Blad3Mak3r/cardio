package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.currentCoroutineContext

open class CardioRepository<C : Cardio>(val db: C) {

    protected suspend fun <T> transaction(block: suspend CardioTransaction.() -> T): T {
        return db.inTransaction(block)
    }

    protected suspend fun <T> query(
        stmt: String,
        args: List<Any?> = emptyList(),
        transform: (Row, RowMetadata) -> T
    ): List<T> {
        return when (val ctx = currentCoroutineContext()[CardioTransaction.Context]?.tx) {
            null -> db.withConnection { connection ->
                val tx = CardioTransaction(connection)
                tx.query(stmt, args, transform)
            }
            else -> ctx.query(stmt, args, transform)
        }
    }

    protected suspend fun execute(stmt: String, args: List<Any?> = emptyList()): Long {
        return when (val ctx = currentCoroutineContext()[CardioTransaction.Context]?.tx) {
            null -> db.withConnection { connection ->
                val tx = CardioTransaction(connection)
                tx.execute(stmt, args)
            }
            else -> ctx.execute(stmt, args)
        }
    }

}