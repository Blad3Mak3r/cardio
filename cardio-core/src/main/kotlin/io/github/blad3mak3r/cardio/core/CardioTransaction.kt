package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.Param
import io.github.blad3mak3r.cardio.protocol.connection.ConnectionPool

class CardioTransaction internal constructor(
    private val conn: ConnectionPool.PooledConnection,
) {
    suspend fun <T> query(
        sql: String,
        vararg params: Param<*>,
        mapper: (Row) -> T,
    ): List<T> = conn.query(sql, *params, mapper = mapper)

    suspend fun <T> queryOne(
        sql: String,
        vararg params: Param<*>,
        mapper: (Row) -> T,
    ): T? = query(sql, *params, mapper = mapper).firstOrNull()

    suspend fun execute(
        sql: String,
        vararg params: Param<*>,
    ): Long = conn.execute(sql, *params)

    suspend fun commit() = conn.commitTransaction()
    suspend fun rollback() = conn.rollbackTransaction()
}