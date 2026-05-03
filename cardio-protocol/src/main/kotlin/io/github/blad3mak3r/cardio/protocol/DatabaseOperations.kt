package io.github.blad3mak3r.cardio.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for executing SQL queries and commands against a PostgreSQL database.
 *
 * Implemented by [io.github.blad3mak3r.cardio.protocol.connection.Connection],
 * `Cardio`, `CardioTransaction`, and `CardioRepository` from the `cardio-core` module.
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
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T
    ): List<T>

    /**
     * Convenience overload that returns the first row mapped by [mapper], or `null`
     * if the query produces no rows.
     *
     * Implementations should optimize this at the wire level by sending
     * `Execute(maxRows = 1)` so the server does not stream the entire result set.
     *
     * @param sql    The SQL query string. Use `$1`, `$2`, … for positional parameters.
     * @param params Query parameter values.
     * @param mapper Function applied to the first [Row] of the result set.
     * @return The mapped value of the first row, or `null` if the result set is empty.
     */
    suspend fun <T> queryOne(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T
    ): T?

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
        params: List<Any?> = emptyList(),
    ): Long

    /**
     * Executes a DML command with a `RETURNING` clause, maps each returned row through
     * [mapper], and returns the complete list of mapped values.
     *
     * Use this instead of [execute] when your INSERT / UPDATE / DELETE statement
     * includes `RETURNING`.
     *
     * @param sql    The SQL command string with `RETURNING`. Use `$1`, `$2`, … for parameters.
     * @param params Command parameter values.
     * @param mapper Function applied to each returned [Row].
     * @return List of values produced by [mapper], one per returned row.
     */
    suspend fun <T> executeReturning(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T
    ): List<T>

    /**
     * Returns a cold [Flow] that streams result rows one chunk at a time using a
     * wire-level cursor (`Execute(maxRows = chunkSize)`).
     *
     * Suitable for large result sets where loading all rows into memory at once is
     * undesirable.  The underlying connection is held for the entire duration of
     * collection; collecting the flow a second time issues a new query.
     *
     * @param sql       The SQL query string. Use `$1`, `$2`, … for positional parameters.
     * @param params    Query parameter values.
     * @param chunkSize Number of rows to fetch per `Execute` round-trip. Defaults to `100`.
     * @param mapper    Function applied to each [Row].
     * @return A cold [Flow] that emits mapped values as rows arrive from the server.
     */
    fun <T> queryFlow(
        sql: String,
        params: List<Any?> = emptyList(),
        chunkSize: Int = 100,
        mapper: (Row) -> T
    ): Flow<T>
}
