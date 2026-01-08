package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.spi.Connection
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitFirstOrNull

suspend inline fun <T> Connection.use(block: suspend (Connection) -> T): T {
    return try {
        block(this)
    } finally {
        close().awaitFirstOrNull()
    }
}

inline fun <reified T> Row.getAsNullable(name: String): T? {
    return try {
        this.get(name, T::class.java)
    } catch (e: Exception) {
        // If column doesn't exist, provide helpful error with available columns
        val metadata = try {
            this.metadata
        } catch (_: Exception) {
            null
        }
        val availableColumns = metadata?.columnMetadatas?.map { it.name } ?: emptyList()
        throw CardioColumnNotFoundException(name, availableColumns)
    }
}

inline fun <reified T> Row.getAs(name: String): T {
    val value = getAsNullable<T>(name)
    if (value == null) {
        val metadata = try {
            this.metadata
        } catch (_: Exception) {
            null
        }
        val availableColumns = metadata?.columnMetadatas?.map { it.name }
        throw CardioNullColumnException(name, availableColumns)
    }
    return value
}