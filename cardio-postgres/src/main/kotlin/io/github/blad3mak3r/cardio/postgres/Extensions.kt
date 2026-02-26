package io.github.blad3mak3r.cardio.postgres

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.Deferred

typealias Row = io.vertx.sqlclient.Row

suspend fun <T> SqlConnection.use(block: suspend (SqlConnection) -> T): T {
    return try {
        block(this)
    } finally {
        close().coAwait()
    }
}

/**
 * Extension function to get a column value as a nullable type.
 * It uses the reified type parameter to determine the expected return type.
 * If the column value is null, it will return null; otherwise, it will return the value cast to the expected type.
 */
inline fun <reified T> Row.getAsNullable(name: String): T? {
    return this.get(T::class.java, name)
}

/**
 * Extension function to get a column value as a non-nullable type.
 * It uses the reified type parameter to determine the expected return type.
 * If the column value is null, it will throw an exception; otherwise, it will return the value cast to the expected type.
 */
inline fun <reified T> Row.getAs(name: String): T {
    return getAsNullable(name) ?: error("Column '$name' is null")
}

/**
 * Extension function to convert a Deferred<T> to a Result<T>.
 * It will return Result.success(value) if the Deferred completes successfully,
 * or Result.failure(exception) if it throws an exception.
 */
suspend inline fun <reified T> Deferred<T>.awaitResult(): Result<T> {
    return try {
        Result.success(this.await())
    } catch (e: Throwable) {
        Result.failure(e)
    }
}