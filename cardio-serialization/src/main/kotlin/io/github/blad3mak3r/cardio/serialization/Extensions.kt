package io.github.blad3mak3r.cardio.serialization

import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.CardioRepository
import io.github.blad3mak3r.cardio.core.CardioTransaction

/**
 * Executes [sql] and maps every result row to [T] using [CardioSerializationFormat],
 * eliminating the need for a manual mapper lambda.
 *
 * @param sql    PostgreSQL query using positional parameters (`$1`, `$2`, …).
 * @param params Parameter values in positional order.
 * @return List of [T] instances decoded from the result set.
 */
suspend inline fun <reified T> Cardio.query(
    sql: String,
    vararg params: Any?
) = this.query(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Executes [sql] and returns the first result row decoded as [T], or `null` if the result
 * set is empty.
 *
 * @param sql    PostgreSQL query using positional parameters.
 * @param params Parameter values in positional order.
 * @return The decoded first row, or `null` if no rows were returned.
 */
suspend inline fun <reified T> Cardio.queryOne(
    sql: String,
    vararg params: Any?
) = this.queryOne(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Executes [sql] inside this transaction and maps every result row to [T] using
 * [CardioSerializationFormat].
 *
 * @param sql    PostgreSQL query using positional parameters.
 * @param params Parameter values in positional order.
 * @return List of [T] instances decoded from the result set.
 */
suspend inline fun <reified T> CardioTransaction.query(
    sql: String,
    vararg params: Any?
) = this.query(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Executes [sql] inside this transaction and returns the first result row decoded as [T],
 * or `null` if the result set is empty.
 *
 * @param sql    PostgreSQL query using positional parameters.
 * @param params Parameter values in positional order.
 * @return The decoded first row, or `null` if no rows were returned.
 */
suspend inline fun <reified T> CardioTransaction.queryOne(
    sql: String,
    vararg params: Any?
) = this.queryOne(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Executes [sql] on this repository and maps every result row to [T] using
 * [CardioSerializationFormat].
 *
 * @param sql    PostgreSQL query using positional parameters.
 * @param params Parameter values in positional order.
 * @return List of [T] instances decoded from the result set.
 */
suspend inline fun <reified T> CardioRepository<*>.query(
    sql: String,
    vararg params: Any?,
) = this.query(sql = sql, params = params) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}

/**
 * Executes [sql] on this repository and returns the first result row decoded as [T],
 * or `null` if the result set is empty.
 *
 * @param sql    PostgreSQL query using positional parameters.
 * @param params Parameter values in positional order.
 * @return The decoded first row, or `null` if no rows were returned.
 */
suspend inline fun <reified T> CardioRepository<*>.queryOne(
    sql: String,
    vararg params: Any?,
) = this.queryOne(sql = sql, params = params) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}