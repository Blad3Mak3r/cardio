package io.github.blad3mak3r.cardio.postgres

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection

typealias Row = io.vertx.sqlclient.Row

suspend fun <T> SqlConnection.use(block: suspend (SqlConnection) -> T): T {
    return try {
        block(this)
    } finally {
        close().coAwait()
    }
}

inline fun <reified T> Row.getAsNullable(name: String): T? {
    return this.get(T::class.java, name)
}

inline fun <reified T> Row.getAs(name: String): T {
    return getAsNullable(name) ?: error("Column '$name' is null")
}