package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.spi.Row

inline fun <reified T> Row.getAsNullable(name: String): T? {
    return this.get(name, T::class.java)
}

inline fun <reified T> Row.getAs(name: String): T {
    return getAsNullable(name) ?: error("")
}