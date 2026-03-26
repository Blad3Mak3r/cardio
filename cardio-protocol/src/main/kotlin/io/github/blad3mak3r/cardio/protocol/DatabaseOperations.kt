package io.github.blad3mak3r.cardio.protocol

interface DatabaseOperations {

    suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T
    ): List<T>

    suspend fun <T> queryOne(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T
    ): T? = query(sql = sql, params = params, mapper = mapper).firstOrNull()

    suspend fun execute(
        sql: String,
        vararg params: Any?,
    ): Long
}