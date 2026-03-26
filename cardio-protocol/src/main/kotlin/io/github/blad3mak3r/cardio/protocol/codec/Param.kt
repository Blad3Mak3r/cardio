package io.github.blad3mak3r.cardio.protocol.codec

import io.github.blad3mak3r.cardio.protocol.EncodedParam
import io.github.blad3mak3r.cardio.protocol.ResultFormat
import kotlin.uuid.ExperimentalUuidApi

class Param<T : Any> @PublishedApi internal constructor(
    @PublishedApi internal val value: T?,
    @PublishedApi internal val codec: TypeCodec<T>
) {
    internal fun encode(): EncodedParam = when (value) {
        null -> EncodedParam(bytes = null)
        else -> EncodedParam(bytes = codec.encode(value), format = ResultFormat.BINARY)
    }

    internal val oid: Int
        get() = codec.oid
}

// Con codec explícito
fun <T : Any> Param(value: T,  codec: TypeCodec<T>): Param<T> = Param(value, codec)

// Conveniencia — el codec se infiere del tipo
fun Param(value: Int):                 Param<Int>                 = Param(value, Int4Codec)
fun Param(value: Long):                Param<Long>                = Param(value, Int8Codec)
fun Param(value: String):              Param<String>              = Param(value, TextCodec)
fun Param(value: Boolean):             Param<Boolean>             = Param(value, BoolCodec)
fun Param(value: Double):              Param<Double>              = Param(value, Float8Codec)
fun Param(value: ByteArray):           Param<ByteArray>           = Param(value, ByteArrayCodec)
fun Param(value: java.util.UUID):      Param<java.util.UUID>      = Param(value, JavaUuidCodec)
@OptIn(ExperimentalUuidApi::class)
fun Param(value: kotlin.uuid.Uuid):    Param<kotlin.uuid.Uuid>    = Param(value, KotlinUuidCodec)
fun Param(value: java.time.Instant):   Param<java.time.Instant>   = Param(value, InstantCodec)
fun Param(value: java.time.LocalDate): Param<java.time.LocalDate> = Param(value, LocalDateCodec)