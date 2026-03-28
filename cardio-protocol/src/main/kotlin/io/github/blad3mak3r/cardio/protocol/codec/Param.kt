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
fun <T : Any> Param(value: T,  codec: TypeCodec<T>): Param<T> = Param(value as T?, codec)

// Conveniencia — el codec se infiere del tipo
fun Param(value: Int):                 Param<Int>                 = Param(value, Int4Codec)
fun Param(value: Short):               Param<Short>               = Param(value, Int2Codec)
fun Param(value: Long):                Param<Long>                = Param(value, Int8Codec)
fun Param(value: String):              Param<String>              = Param(value, TextCodec)
fun Param(value: Boolean):             Param<Boolean>             = Param(value, BoolCodec)
fun Param(value: Float):               Param<Float>               = Param(value, Float4Codec)
fun Param(value: Double):              Param<Double>              = Param(value, Float8Codec)
fun Param(value: ByteArray):           Param<ByteArray>           = Param(value, ByteArrayCodec)
@OptIn(ExperimentalUuidApi::class)
fun Param(value: kotlin.uuid.Uuid):    Param<kotlin.uuid.Uuid>    = Param(value, KotlinUuidCodec)
fun Param(value: java.time.Instant):   Param<java.time.Instant>   = Param(value, InstantCodec)
fun Param(value: java.time.LocalDate): Param<java.time.LocalDate> = Param(value, LocalDateCodec)

// Conveniencia para arrays — tipos escalares comunes
@JvmName("ParamIntList")
fun Param(value: List<Int>):               Param<List<Int>>               = Param(value, Int4ArrayCodec)
@JvmName("ParamShortList")
fun Param(value: List<Short>):             Param<List<Short>>             = Param(value, Int2ArrayCodec)
@JvmName("ParamLongList")
fun Param(value: List<Long>):              Param<List<Long>>              = Param(value, Int8ArrayCodec)
@JvmName("ParamFloatList")
fun Param(value: List<Float>):             Param<List<Float>>             = Param(value, Float4ArrayCodec)
@JvmName("ParamDoubleList")
fun Param(value: List<Double>):            Param<List<Double>>            = Param(value, Float8ArrayCodec)
@JvmName("ParamStringList")
fun Param(value: List<String>):            Param<List<String>>            = Param(value, TextArrayCodec)
@JvmName("ParamBooleanList")
fun Param(value: List<Boolean>):           Param<List<Boolean>>           = Param(value, BoolArrayCodec)
@JvmName("ParamInstantList")
fun Param(value: List<java.time.Instant>): Param<List<java.time.Instant>> = Param(value, TimestamptzArrayCodec)

// Primitive array sugar (IntArray, LongArray, …)
fun Param(value: IntArray):     Param<List<Int>>     = Param(value.toList(), Int4ArrayCodec)
fun Param(value: ShortArray):   Param<List<Short>>   = Param(value.toList(), Int2ArrayCodec)
fun Param(value: LongArray):    Param<List<Long>>    = Param(value.toList(), Int8ArrayCodec)
fun Param(value: FloatArray):   Param<List<Float>>   = Param(value.toList(), Float4ArrayCodec)
fun Param(value: DoubleArray):  Param<List<Double>>  = Param(value.toList(), Float8ArrayCodec)
fun Param(value: BooleanArray): Param<List<Boolean>> = Param(value.toList(), BoolArrayCodec)

/**
 * Convierte cualquier valor al [Param] correspondiente de forma automática.
 *
 * - Si el valor ya es un [Param], se devuelve tal cual (útil cuando el usuario
 *   quiere pasar un codec explícito con `Param(x, MyCodec)`).
 * - Si el valor es `null`, se genera un parámetro nulo con OID no especificado (0),
 *   dejando que el servidor infiera el tipo.
 * - Para todos los tipos primitivos soportados la resolución es automática.
 * - Para [List] se infiere el codec a partir del tipo del primer elemento no nulo.
 * - Para tipos primitivos de arrays (`IntArray`, `LongArray`, …) se convierten
 *   a lista automáticamente.
 * - Para tipos desconocidos se lanza un error descriptivo que indica cómo usar
 *   `Param(value, codec)` directamente.
 */
@OptIn(ExperimentalUuidApi::class)
@Suppress("UNCHECKED_CAST")
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
    is kotlin.uuid.Uuid    -> Param(this, KotlinUuidCodec)
    is java.time.Instant   -> Param(this, InstantCodec)
    is java.time.LocalDate -> Param(this, LocalDateCodec)
    // Primitive Kotlin arrays
    is IntArray            -> Param(this.toList(), Int4ArrayCodec)
    is ShortArray          -> Param(this.toList(), Int2ArrayCodec)
    is LongArray           -> Param(this.toList(), Int8ArrayCodec)
    is FloatArray          -> Param(this.toList(), Float4ArrayCodec)
    is DoubleArray         -> Param(this.toList(), Float8ArrayCodec)
    is BooleanArray        -> Param(this.toList(), BoolArrayCodec)
    // Generic List — codec inferred from the first non-null element
    is List<*> -> {
        val first = this.firstOrNull { it != null }
        when {
            this.isEmpty() || first == null -> Param(emptyList<String>(), TextArrayCodec)
            first is Int            -> Param(this.filterNotNull() as List<Int>,               Int4ArrayCodec)
            first is Short          -> Param(this.filterNotNull() as List<Short>,             Int2ArrayCodec)
            first is Long           -> Param(this.filterNotNull() as List<Long>,              Int8ArrayCodec)
            first is Float          -> Param(this.filterNotNull() as List<Float>,             Float4ArrayCodec)
            first is Double         -> Param(this.filterNotNull() as List<Double>,            Float8ArrayCodec)
            first is String         -> Param(this.filterNotNull() as List<String>,            TextArrayCodec)
            first is Boolean        -> Param(this.filterNotNull() as List<Boolean>,           BoolArrayCodec)
            first is kotlin.uuid.Uuid -> Param(this.filterNotNull() as List<kotlin.uuid.Uuid>, KotlinUuidArrayCodec)
            first is java.time.Instant -> Param(this.filterNotNull() as List<java.time.Instant>, TimestamptzArrayCodec)
            else -> error(
                "No built-in array codec for List element type '${first::class.qualifiedName}'. " +
                "Use Param(list, ArrayCodec(arrayOid, elementCodec)) to provide an explicit codec."
            )
        }
    }
    else -> error(
        "No built-in codec for type '${this::class.qualifiedName}'. " +
        "Use Param(value, codec) to provide an explicit TypeCodec."
    )
}
