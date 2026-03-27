package io.github.blad3mak3r.cardio.serialization

import io.github.blad3mak3r.cardio.core.Cardio
import io.github.blad3mak3r.cardio.core.CardioRepository
import io.github.blad3mak3r.cardio.core.CardioTransaction

suspend inline fun <reified T> Cardio.query(
    sql: String,
    vararg params: Any?
) = this.query(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

suspend inline fun <reified T> Cardio.queryOne(
    sql: String,
    vararg params: Any?
) = this.queryOne(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

suspend inline fun <reified T> CardioTransaction.query(
    sql: String,
    vararg params: Any?
) = this.query(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

suspend inline fun <reified T> CardioTransaction.queryOne(
    sql: String,
    vararg params: Any?
) = this.queryOne(sql = sql, params = params) { r ->
    CardioSerializationFormat.decodeFromRow<T>(r)
}

suspend inline fun <reified T> CardioRepository<*>.query(
    sql: String,
    vararg params: Any?,
) = query(sql, *params) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}

suspend inline fun <reified T> CardioRepository<*>.queryOne(
    sql: String,
    vararg params: Any?,
) = queryOne(sql, *params) { row ->
    CardioSerializationFormat.decodeFromRow<T>(row)
}