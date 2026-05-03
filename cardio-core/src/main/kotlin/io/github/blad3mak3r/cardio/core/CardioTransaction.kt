package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext

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
     * [CoroutineContext.Element] that carries an active [CardioTransaction] through the coroutine
     * hierarchy.  Set by [Cardio.inTransaction] so that nested calls and [Cardio] method
     * invocations can automatically join the same transaction without an explicit parameter.
     */
    class Context(val transaction: CardioTransaction) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = Key
        companion object Key : CoroutineContext.Key<Context>
    }

    override suspend fun <T> query(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T,
    ): List<T> = conn.query(sql, params, mapper)

    override suspend fun <T> queryOne(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T,
    ): T? = conn.queryOne(sql, params, mapper)

    override suspend fun execute(
        sql: String,
        params: List<Any?>,
    ): Long = conn.execute(sql, params)

    override suspend fun <T> executeReturning(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T,
    ): List<T> = conn.executeReturning(sql, params, mapper)

    override fun <T> queryFlow(
        sql: String,
        params: List<Any?>,
        chunkSize: Int,
        mapper: (Row) -> T,
    ): Flow<T> = conn.queryFlow(sql, params, chunkSize, mapper)

    /** Explicitly commits the current transaction by sending `COMMIT` to the server. */
    suspend fun commit() = conn.commitTransaction()
    /** Explicitly rolls back the current transaction by sending `ROLLBACK` to the server. */
    suspend fun rollback() = conn.rollbackTransaction()
}
