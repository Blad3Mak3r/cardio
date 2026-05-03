package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.Row
import kotlinx.coroutines.flow.Flow

/**
 * Abstract base class for repository objects that encapsulate data-access logic.
 *
 * Subclass [CardioRepository] and inject a [Cardio] (or a typed subclass) to group
 * related SQL operations in one place.  [query], [execute], [executeReturning], and
 * [inTransaction] are forwarded to the underlying [Cardio] instance.
 *
 * ```kotlin
 * class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {
 *     suspend fun findById(id: Int) = queryOne("SELECT * FROM users WHERE id = $1", listOf(id)) { row ->
 *         User(row.get("id"), row.get("name"))
 *     }
 * }
 * ```
 *
 * @param C The concrete [Cardio] subtype managed by this repository.
 * @param db The [Cardio] instance (or subclass) to delegate to.
 */
abstract class CardioRepository<C : Cardio>(protected val db: C) : DatabaseOperations {

    override suspend fun <T> query(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T,
    ): List<T> = db.query(sql, params, mapper)

    override suspend fun <T> queryOne(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T,
    ): T? = db.queryOne(sql, params, mapper)

    override suspend fun execute(
        sql: String,
        params: List<Any?>,
    ): Long = db.execute(sql, params)

    override suspend fun <T> executeReturning(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T,
    ): List<T> = db.executeReturning(sql, params, mapper)

    override fun <T> queryFlow(
        sql: String,
        params: List<Any?>,
        chunkSize: Int,
        mapper: (Row) -> T,
    ): Flow<T> = db.queryFlow(sql, params, chunkSize, mapper)

    /**
     * Begins a transaction on [db], runs [block], and commits on success (rolls back on exception).
     *
     * Nested calls automatically join the existing transaction rather than starting a new one.
     *
     * @param block Suspending extension lambda on [CardioTransaction].
     * @return The value returned by [block].
     */
    protected suspend fun <T> inTransaction(
        block: suspend CardioTransaction.() -> T,
    ): T = db.inTransaction(block)
}
