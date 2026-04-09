package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.Row

/**
 * Abstract base class for repository objects that encapsulate data-access logic.
 *
 * Subclass [CardioRepository] and inject a [Cardio] (or a typed subclass) to group
 * related SQL operations in one place.  [query], [execute], and [inTransaction] are
 * forwarded to the underlying [Cardio] instance.
 *
 * ```kotlin
 * class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {
 *     suspend fun findById(id: Int) = queryOne("SELECT * FROM users WHERE id = $1", id) { row ->
 *         User(row.get("id"), row.get("name"))
 *     }
 * }
 * ```
 *
 * @param C The concrete [Cardio] subtype managed by this repository.
 * @param db The [Cardio] instance (or subclass) to delegate to.
 */
abstract class CardioRepository<C : Cardio>(protected val db: C) : DatabaseOperations {

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
    ): List<T> = db.query(sql, *params, mapper = mapper)

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
    ): Long = db.execute(sql, *params)

    /**
     * Begins a transaction on [db], runs [block], and commits on success (rolls back on exception).
     *
     * @param block Suspending lambda that receives a [CardioTransaction] handle.
     * @return The value returned by [block].
     */
    protected suspend fun <T> inTransaction(
        block: suspend (CardioTransaction) -> T,
    ): T = db.inTransaction(block)
}