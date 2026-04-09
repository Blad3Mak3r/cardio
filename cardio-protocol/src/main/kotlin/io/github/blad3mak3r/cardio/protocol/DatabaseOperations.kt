package io.github.blad3mak3r.cardio.protocol

/**
 * Common interface for executing SQL queries and commands against a PostgreSQL database.
 *
 * Implemented by [io.github.blad3mak3r.cardio.protocol.connection.Connection],
 * [io.github.blad3mak3r.cardio.core.Cardio], [io.github.blad3mak3r.cardio.core.CardioTransaction],
 * and [io.github.blad3mak3r.cardio.core.CardioRepository].
 *
 * All operations use PostgreSQL native positional parameters (`$1`, `$2`, …).
 */
interface DatabaseOperations {

    /**
     * Executes [sql] with the given [params], maps each result row through [mapper],
     * and returns the complete list of mapped values.
     *
     * @param sql    The SQL query string. Use `$1`, `$2`, … for positional parameters.
     * @param params Query parameter values. Each value is encoded using the built-in
     *               codec for its type. Wrap in [io.github.blad3mak3r.cardio.protocol.codec.Param]
     *               to supply an explicit codec.
     * @param mapper Function applied to each [Row] in the result set.
     * @return List of values produced by [mapper], one per result row. Empty if no rows matched.
     */
    suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T
    ): List<T>

    /**
     * Convenience overload that returns the first row mapped by [mapper], or `null`
     * if the query produces no rows.
     *
     * @param sql    The SQL query string. Use `$1`, `$2`, … for positional parameters.
     * @param params Query parameter values.
     * @param mapper Function applied to the first [Row] of the result set.
     * @return The mapped value of the first row, or `null` if the result set is empty.
     */
    suspend fun <T> queryOne(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T
    ): T? = query(sql = sql, params = params, mapper = mapper).firstOrNull()

    /**
     * Executes a SQL command (INSERT, UPDATE, DELETE, DDL, …) with the given [params]
     * and returns the number of rows affected.
     *
     * @param sql    The SQL command string. Use `$1`, `$2`, … for positional parameters.
     * @param params Command parameter values.
     * @return Number of rows affected by the command, as reported by the server.
     */
    suspend fun execute(
        sql: String,
        vararg params: Any?,
    ): Long
}