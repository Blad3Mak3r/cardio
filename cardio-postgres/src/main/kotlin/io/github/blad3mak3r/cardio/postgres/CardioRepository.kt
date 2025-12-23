package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata

open class CardioRepository<C : Cardio>(val db: C) {

    internal suspend fun <T> transaction(block: suspend CardioTransaction.() -> T): T {
        return db.inTransaction(block)
    }

    internal suspend fun <T> query(
        stmt: String,
        args: List<Any?> = emptyList(),
        transform: (Row, RowMetadata) -> T
    ): List<T> {
        return db.withConnection { connection ->
            val tx = CardioTransaction(connection)
            tx.query(stmt, args, transform)
        }
    }

    internal suspend fun execute(stmt: String, args: List<Any?> = emptyList()): Long {
        return db.withConnection { connection ->
            val tx = CardioTransaction(connection)
            tx.execute(stmt, args)
        }
    }

}