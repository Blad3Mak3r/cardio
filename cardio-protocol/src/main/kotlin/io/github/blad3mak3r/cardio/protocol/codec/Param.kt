package io.github.blad3mak3r.cardio.protocol.codec

import io.github.blad3mak3r.cardio.protocol.EncodedParam
import io.github.blad3mak3r.cardio.protocol.ResultFormat
import kotlin.uuid.ExperimentalUuidApi

/**
 * A typed query parameter that pairs a value with its [TypeCodec].
 *
 * [Param] instances are passed as query parameters to
 * [io.github.blad3mak3r.cardio.protocol.DatabaseOperations.query] and
 * [io.github.blad3mak3r.cardio.protocol.DatabaseOperations.execute].
 * Use the factory functions (e.g. [Param], [Any?.toParam]) rather than the constructor directly.
 *
 * When [value] is `null`, [encode] produces a SQL NULL parameter (binary representation absent,
 * length = −1 in the Bind message). Otherwise the value is encoded using [codec].
 *
 * @param T     The Kotlin type of the parameter value.
 * @param value The parameter value to send; `null` results in SQL NULL.
 * @param codec The [TypeCodec] used to encode [value] into PostgreSQL binary format.
 */
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

/**
 * Creates a [Param] with an explicit [codec].
 *
 * Use this overload when the type cannot be automatically resolved by [Any?.toParam], for example
 * for custom enum types, domain types, or non-standard array element types:
 * ```kotlin
 * Param(myEnum.name, TextCodec)
 * Param(myList, ArrayCodec(PgOid.TEXT_ARRAY, MyEnumCodec))
 * ```
 *
 * @param value The value to encode. Must not be `null`; for SQL NULL pass `null` to [Param] directly.
 * @param codec The codec to use for encoding.
 */
fun <T : Any> Param(value: T,  codec: TypeCodec<T>): Param<T> = Param(value as T?, codec)

// Convenience overloads — the codec is inferred from the value type.
// For automatic codec resolution from Any?, use Any?.toParam() instead.
fun Param(value: Int):                       Param<Int>                       = Param(value, Int4Codec)
fun Param(value: Short):                     Param<Short>                     = Param(value, Int2Codec)
fun Param(value: Long):                      Param<Long>                      = Param(value, Int8Codec)
fun Param(value: String):                    Param<String>                    = Param(value, TextCodec)
fun Param(value: Boolean):                   Param<Boolean>                   = Param(value, BoolCodec)
fun Param(value: Float):                     Param<Float>                     = Param(value, Float4Codec)
fun Param(value: Double):                    Param<Double>                    = Param(value, Float8Codec)
fun Param(value: ByteArray):                 Param<ByteArray>                 = Param(value, ByteArrayCodec)
@OptIn(ExperimentalUuidApi::class)
fun Param(value: kotlin.uuid.Uuid):          Param<kotlin.uuid.Uuid>          = Param(value, KotlinUuidCodec)
fun Param(value: kotlin.time.Instant):        Param<kotlin.time.Instant>        = Param(value, InstantCodec)
fun Param(value: kotlinx.datetime.LocalDate): Param<kotlinx.datetime.LocalDate> = Param(value, LocalDateCodec)

// Convenience overloads for common collection and array types.
// Kotlin primitive arrays (IntArray, LongArray, …) are automatically converted to List<T>.
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
fun Param(value: List<kotlin.time.Instant>): Param<List<kotlin.time.Instant>> = Param(value, TimestamptzArrayCodec)

// Primitive array sugar (IntArray, LongArray, …)
fun Param(value: IntArray):     Param<List<Int>>     = Param(value.toList(), Int4ArrayCodec)
fun Param(value: ShortArray):   Param<List<Short>>   = Param(value.toList(), Int2ArrayCodec)
fun Param(value: LongArray):    Param<List<Long>>    = Param(value.toList(), Int8ArrayCodec)
fun Param(value: FloatArray):   Param<List<Float>>   = Param(value.toList(), Float4ArrayCodec)
fun Param(value: DoubleArray):  Param<List<Double>>  = Param(value.toList(), Float8ArrayCodec)
fun Param(value: BooleanArray): Param<List<Boolean>> = Param(value.toList(), BoolArrayCodec)

// Typed Array<T> sugar — mirrors the List<T> overloads above
@JvmName("ParamIntArray")
fun Param(value: Array<Int>):                 Param<List<Int>>                 = Param(value.toList(), Int4ArrayCodec)
@JvmName("ParamShortArray")
fun Param(value: Array<Short>):               Param<List<Short>>               = Param(value.toList(), Int2ArrayCodec)
@JvmName("ParamLongArray")
fun Param(value: Array<Long>):                Param<List<Long>>                = Param(value.toList(), Int8ArrayCodec)
@JvmName("ParamFloatArray")
fun Param(value: Array<Float>):               Param<List<Float>>               = Param(value.toList(), Float4ArrayCodec)
@JvmName("ParamDoubleArray")
fun Param(value: Array<Double>):              Param<List<Double>>              = Param(value.toList(), Float8ArrayCodec)
@JvmName("ParamStringArray")
fun Param(value: Array<String>):              Param<List<String>>              = Param(value.toList(), TextArrayCodec)
@JvmName("ParamBooleanArray")
fun Param(value: Array<Boolean>):             Param<List<Boolean>>             = Param(value.toList(), BoolArrayCodec)
@JvmName("ParamInstantArray")
fun Param(value: Array<kotlin.time.Instant>): Param<List<kotlin.time.Instant>> = Param(value.toList(), TimestamptzArrayCodec)
@OptIn(ExperimentalUuidApi::class)
@JvmName("ParamKotlinUuidArray")
fun Param(value: Array<kotlin.uuid.Uuid>):    Param<List<kotlin.uuid.Uuid>>    = Param(value.toList(), KotlinUuidArrayCodec)

/**
 * Automatically converts any supported Kotlin value to its corresponding [Param].
 *
 * Resolution rules (in order):
 * - If `this` is already a [Param], it is returned unchanged.
 * - If `this` is `null`, a SQL NULL parameter using [TextCodec] (OID 0 = untyped) is returned.
 * - Scalar types ([Int], [Short], [Long], [Float], [Double], [String], [Boolean], [ByteArray],
 *   [kotlin.uuid.Uuid], [kotlin.time.Instant], [kotlinx.datetime.LocalDate]) resolve to their
 *   corresponding built-in scalar codec.
 * - Kotlin primitive arrays ([IntArray], [ShortArray], [LongArray], [FloatArray], [DoubleArray],
 *   [BooleanArray]) are converted to `List<T>` and resolved as below.
 * - [Array]`<T>` is converted to `List<T>` recursively.
 * - [List]`<T>` resolves the codec from the type of the first non-null element. An empty or
 *   all-null list defaults to [TextArrayCodec].
 * - Any other type throws [IllegalStateException] with a message that explains how to supply an
 *   explicit codec using `Param(value, codec)`.
 *
 * @throws IllegalStateException if the runtime type of `this` has no built-in codec.
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
    is kotlin.uuid.Uuid              -> Param(this, KotlinUuidCodec)
    is kotlin.time.Instant           -> Param(this, InstantCodec)
    is kotlinx.datetime.LocalDate    -> Param(this, LocalDateCodec)
    // Primitive Kotlin arrays
    is IntArray            -> Param(this.toList(), Int4ArrayCodec)
    is ShortArray          -> Param(this.toList(), Int2ArrayCodec)
    is LongArray           -> Param(this.toList(), Int8ArrayCodec)
    is FloatArray          -> Param(this.toList(), Float4ArrayCodec)
    is DoubleArray         -> Param(this.toList(), Float8ArrayCodec)
    is BooleanArray        -> Param(this.toList(), BoolArrayCodec)
    // Generic List — codec inferred from the first non-null element
    is Array<*> -> this.toList().toParam()
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
            first is kotlin.time.Instant -> Param(this.filterNotNull() as List<kotlin.time.Instant>, TimestamptzArrayCodec)
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
