package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.connection.Connection

/**
 * Handle for a live PostgreSQL transaction, exposing [DatabaseOperations] for the duration
 * of a [Cardio.inTransaction] call.
 *
 * Instances are created internally by [Cardio.inTransaction] and should not be constructed
 * directly.  The underlying [Connection] is still active and in an open transaction when
 * this object is passed to the user's lambda.
 *
 * [query] and [execute] delegate directly to the underlying connection.
 * [commit] and [rollback] allow explicit transaction control; [Cardio.inTransaction] also
 * performs an automatic `ROLLBACK` if the lambda throws, and an automatic `COMMIT` on success.
 */
class CardioTransaction internal constructor(
    private val conn: Connection,
) : DatabaseOperations {

    /**
     * Executes [sql] and maps every result row to a value using [mapper].
     *
     * @param sql    PostgreSQL query using positional parameters (`$1`, `$2`, …).
     * @param params Parameter values in positional order.
     * @param mapper Transformation applied to each result row.
     * @return List of values produced by [mapper].
     */
    override suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T,
    ): List<T> = conn.query(sql, *params, mapper = mapper)

    /**
     * Executes [sql] as a DML/DDL statement and returns the number of affected rows.
     *
     * @param sql    PostgreSQL statement using positional parameters (`$1`, `$2`, …).
     * @param params Parameter values in positional order.
     * @return Number of rows affected.
     */
    override suspend fun execute(
        sql: String,
        vararg params: Any?,
    ): Long = conn.execute(sql, *params)

    /** Explicitly commits the current transaction by sending `COMMIT` to the server. */
    suspend fun commit() = conn.commitTransaction()
    /** Explicitly rolls back the current transaction by sending `ROLLBACK` to the server. */
    suspend fun rollback() = conn.rollbackTransaction()
}