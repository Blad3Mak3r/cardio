package io.github.blad3mak3r.cardio.protocol

sealed interface PgResolver<S, T : PgType<S>> {
    fun resolve(value: Any?): T
}