package io.github.blad3mak3r.cardio.protocol

import kotlin.uuid.ExperimentalUuidApi

sealed interface PgType<T>  {
    @OptIn(ExperimentalUuidApi::class)
    object Uuid : PgType<kotlin.uuid.Uuid>
    object Bytea : PgType<ByteArray>
    object Char : PgType<String>
    object Text : PgType<String>
    object Json : PgType<Any>
    object Jsonb : PgType<Any>
}