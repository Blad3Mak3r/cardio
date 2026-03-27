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
fun Param(value: Short):               Param<Short>               = Param(value, Int2Codec)
fun Param(value: Long):                Param<Long>                = Param(value, Int8Codec)
fun Param(value: String):              Param<String>              = Param(value, TextCodec)
fun Param(value: Boolean):             Param<Boolean>             = Param(value, BoolCodec)
fun Param(value: Float):               Param<Float>               = Param(value, Float4Codec)
fun Param(value: Double):              Param<Double>              = Param(value, Float8Codec)
fun Param(value: ByteArray):           Param<ByteArray>           = Param(value, ByteArrayCodec)
fun Param(value: java.util.UUID):      Param<java.util.UUID>      = Param(value, JavaUuidCodec)
@OptIn(ExperimentalUuidApi::class)
fun Param(value: kotlin.uuid.Uuid):    Param<kotlin.uuid.Uuid>    = Param(value, KotlinUuidCodec)
fun Param(value: java.time.Instant):   Param<java.time.Instant>   = Param(value, InstantCodec)
fun Param(value: java.time.LocalDate): Param<java.time.LocalDate> = Param(value, LocalDateCodec)

/**
 * Convierte cualquier valor al [Param] correspondiente de forma automática.
 *
 * - Si el valor ya es un [Param], se devuelve tal cual (útil cuando el usuario
 *   quiere pasar un codec explícito con `Param(x, MyCodec)`).
 * - Si el valor es `null`, se genera un parámetro nulo con OID no especificado (0),
 *   dejando que el servidor infiera el tipo.
 * - Para todos los tipos primitivos soportados la resolución es automática.
 * - Para tipos desconocidos se lanza un error descriptivo que indica cómo usar
 *   `Param(value, codec)` directamente.
 */
@OptIn(ExperimentalUuidApi::class)
fun Any?.toParam(): Param<*> = when (this) {
    is Param<*>            -> this
    null                   -> Param(null, TextCodec)
    is Int                 -> Param(this, Int4Codec)
    is Short               -> Param(this, Int2Codec)
    is Long                -> Param(this, Int8Codec)
    is Float               -> Param(this, Float4Codec)
    is Double              -> Param(this, Float8Codec)
    is String              -> Param(this, TextCodec)
    is Boolean             -> Param(this, BoolCodec)
    is ByteArray           -> Param(this, ByteArrayCodec)
    is java.util.UUID      -> Param(this, JavaUuidCodec)
    is kotlin.uuid.Uuid    -> Param(this, KotlinUuidCodec)
    is java.time.Instant   -> Param(this, InstantCodec)
    is java.time.LocalDate -> Param(this, LocalDateCodec)
    else -> error(
        "No built-in codec for type '${this::class.qualifiedName}'. " +
        "Use Param(value, codec) to provide an explicit TypeCodec."
    )
}
