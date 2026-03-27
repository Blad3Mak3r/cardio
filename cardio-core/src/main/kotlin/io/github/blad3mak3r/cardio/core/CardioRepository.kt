package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.Row

abstract class CardioRepository<C : Cardio>(protected val db: C) : DatabaseOperations {

    override suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T,
    ): List<T> = db.query(sql, *params, mapper = mapper)

    override suspend fun execute(
        sql: String,
        vararg params: Any?,
    ): Long = db.execute(sql, *params)

    protected suspend fun <T> inTransaction(
        block: suspend (CardioTransaction) -> T,
    ): T = db.inTransaction(block)
}