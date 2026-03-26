package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.connection.Connection

class CardioTransaction internal constructor(
    private val conn: Connection,
) : DatabaseOperations {
    override suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T,
    ): List<T> = conn.query(sql, *params, mapper = mapper)

    override suspend fun execute(
        sql: String,
        vararg params: Any?,
    ): Long = conn.execute(sql, *params)

    suspend fun commit() = conn.commitTransaction()
    suspend fun rollback() = conn.rollbackTransaction()
}