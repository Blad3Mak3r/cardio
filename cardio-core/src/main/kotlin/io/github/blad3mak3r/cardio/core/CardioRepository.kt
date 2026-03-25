package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.Param

abstract class CardioRepository(protected val db: Cardio) {

    protected suspend fun <T> query(
        sql: String,
        vararg params: Param<*>,
        mapper: (Row) -> T,
    ): List<T> = db.query(sql, *params, mapper = mapper)

    protected suspend fun <T> queryOne(
        sql: String,
        vararg params: Param<*>,
        mapper: (Row) -> T,
    ): T? = db.queryOne(sql, *params, mapper = mapper)

    protected suspend fun execute(
        sql: String,
        vararg params: Param<*>,
    ): Long = db.execute(sql, *params)

    protected suspend fun <T> inTransaction(
        block: suspend (CardioTransaction) -> T,
    ): T = db.inTransaction(block)
}