package io.github.blad3mak3r.cardio.serialization

import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.CardioRepository
import io.github.blad3mak3r.cardio.core.CardioTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ── Cardio extensions ────────────────────────────────────────────────────────

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
    params: List<Any?> = emptyList(),
): List<T> = this.query(sql = sql, params = params) { r ->
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
    params: List<Any?> = emptyList(),
): T? = this.queryOne(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Executes a DML statement with `RETURNING` and maps each returned row to [T] using
 * [CardioSerializationFormat].
 *
 * @param sql    PostgreSQL statement with `RETURNING` using positional parameters.
 * @param params Parameter values in positional order.
 * @return List of [T] instances decoded from the returned rows.
 */
suspend inline fun <reified T> Cardio.executeReturning(
    sql: String,
    params: List<Any?> = emptyList(),
): List<T> = this.executeReturning(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Returns a cold [Flow] that streams result rows decoded as [T] using [CardioSerializationFormat].
 *
 * @param sql       PostgreSQL query using positional parameters.
 * @param params    Parameter values in positional order.
 * @param chunkSize Number of rows to fetch per `Execute` round-trip. Defaults to `100`.
 * @return A cold [Flow] emitting [T] instances as rows arrive from the server.
 */
inline fun <reified T> Cardio.queryFlow(
    sql: String,
    params: List<Any?> = emptyList(),
    chunkSize: Int = 100,
): Flow<T> = this.queryFlow(sql = sql, params = params, chunkSize = chunkSize) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

// ── CardioTransaction extensions ─────────────────────────────────────────────

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
    params: List<Any?> = emptyList(),
): List<T> = this.query(sql = sql, params = params) { r ->
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
    params: List<Any?> = emptyList(),
): T? = this.queryOne(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Executes a DML statement with `RETURNING` inside this transaction and maps each returned
 * row to [T] using [CardioSerializationFormat].
 *
 * @param sql    PostgreSQL statement with `RETURNING` using positional parameters.
 * @param params Parameter values in positional order.
 * @return List of [T] instances decoded from the returned rows.
 */
suspend inline fun <reified T> CardioTransaction.executeReturning(
    sql: String,
    params: List<Any?> = emptyList(),
): List<T> = this.executeReturning(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

/**
 * Returns a cold [Flow] streaming rows from this transaction decoded as [T] using
 * [CardioSerializationFormat].
 *
 * @param sql       PostgreSQL query using positional parameters.
 * @param params    Parameter values in positional order.
 * @param chunkSize Number of rows to fetch per `Execute` round-trip. Defaults to `100`.
 * @return A cold [Flow] emitting [T] instances as rows arrive from the server.
 */
inline fun <reified T> CardioTransaction.queryFlow(
    sql: String,
    params: List<Any?> = emptyList(),
    chunkSize: Int = 100,
): Flow<T> = this.queryFlow(sql = sql, params = params, chunkSize = chunkSize) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

// ── CardioRepository extensions ───────────────────────────────────────────────

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
    params: List<Any?> = emptyList(),
): List<T> = this.query(sql = sql, params = params) { row ->
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
    params: List<Any?> = emptyList(),
): T? = this.queryOne(sql = sql, params = params) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}

/**
 * Executes a DML statement with `RETURNING` on this repository and maps each returned row to
 * [T] using [CardioSerializationFormat].
 *
 * @param sql    PostgreSQL statement with `RETURNING` using positional parameters.
 * @param params Parameter values in positional order.
 * @return List of [T] instances decoded from the returned rows.
 */
suspend inline fun <reified T> CardioRepository<*>.executeReturning(
    sql: String,
    params: List<Any?> = emptyList(),
): List<T> = this.executeReturning(sql = sql, params = params) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}

/**
 * Returns a cold [Flow] streaming rows from this repository decoded as [T] using
 * [CardioSerializationFormat].
 *
 * @param sql       PostgreSQL query using positional parameters.
 * @param params    Parameter values in positional order.
 * @param chunkSize Number of rows to fetch per `Execute` round-trip. Defaults to `100`.
 * @return A cold [Flow] emitting [T] instances as rows arrive from the server.
 */
inline fun <reified T> CardioRepository<*>.queryFlow(
    sql: String,
    params: List<Any?> = emptyList(),
    chunkSize: Int = 100,
): Flow<T> = this.queryFlow(sql = sql, params = params, chunkSize = chunkSize) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}
