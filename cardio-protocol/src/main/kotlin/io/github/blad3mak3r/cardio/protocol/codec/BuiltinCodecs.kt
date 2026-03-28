package io.github.blad3mak3r.cardio.protocol.codec

import io.github.blad3mak3r.cardio.protocol.PgOid
import kotlin.uuid.ExperimentalUuidApi

object Int2Codec : TypeCodec<Short> {
    override val oid = PgOid.INT2
    override fun encode(value: Short): ByteArray = byteArrayOf(
        (value.toInt() shr 8).toByte(), value.toByte()
    )
    override fun decode(bytes: ByteArray?): Short? {
        if (bytes == null) return null
        return ((bytes[0].toInt() and 0xFF shl 8) or (bytes[1].toInt() and 0xFF)).toShort()
    }
}

object Int4Codec : TypeCodec<Int> {
    override val oid = PgOid.INT4
    override fun encode(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(),
        (value shr  8).toByte(),  value.toByte()
    )
    override fun decode(bytes: ByteArray?): Int? {
        if (bytes == null) return null
        return (bytes[0].toInt() and 0xFF shl 24) or
                (bytes[1].toInt() and 0xFF shl 16) or
                (bytes[2].toInt() and 0xFF shl  8) or
                (bytes[3].toInt() and 0xFF)
    }
}

object Int8Codec : TypeCodec<Long> {
    override val oid = PgOid.INT8
    override fun encode(value: Long): ByteArray = ByteArray(8) { i ->
        (value shr (56 - i * 8)).toByte()
    }
    override fun decode(bytes: ByteArray?): Long? {
        if (bytes == null) return null
        var r = 0L
        for (b in bytes) r = (r shl 8) or (b.toLong() and 0xFF)
        return r
    }
}

object Float4Codec : TypeCodec<Float> {
    override val oid = PgOid.FLOAT4
    override fun encode(value: Float)      = Int4Codec.encode(java.lang.Float.floatToIntBits(value))
    override fun decode(bytes: ByteArray?) = Int4Codec.decode(bytes)
        ?.let { java.lang.Float.intBitsToFloat(it) }
}

object Float8Codec : TypeCodec<Double> {
    override val oid = PgOid.FLOAT8
    override fun encode(value: Double)     = Int8Codec.encode(java.lang.Double.doubleToLongBits(value))
    override fun decode(bytes: ByteArray?) = Int8Codec.decode(bytes)
        ?.let { java.lang.Double.longBitsToDouble(it) }
}

object TextCodec : TypeCodec<String> {
    override val oid = PgOid.TEXT
    override fun encode(value: String)     = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?) = bytes?.toString(Charsets.UTF_8)
}

object BoolCodec : TypeCodec<Boolean> {
    override val oid = PgOid.BOOL
    override fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)
    override fun decode(bytes: ByteArray?)          = bytes?.let { it[0] != 0.toByte() }
}

object ByteArrayCodec : TypeCodec<ByteArray> {
    override val oid = PgOid.BYTEA
    override fun encode(value: ByteArray)  = value
    override fun decode(bytes: ByteArray?) = bytes
}

// kotlin.uuid.Uuid ↔ 16 bytes big-endian
@OptIn(ExperimentalUuidApi::class)
object KotlinUuidCodec : TypeCodec<kotlin.uuid.Uuid> {
    override val oid = PgOid.UUID
    override fun encode(value: kotlin.uuid.Uuid): ByteArray {
        return value.toLongs { msb, lsb ->
            Int8Codec.encode(msb) + Int8Codec.encode(lsb)
        }
    }
    override fun decode(bytes: ByteArray?): kotlin.uuid.Uuid? {
        if (bytes == null || bytes.size != 16) return null
        val msb = Int8Codec.decode(bytes.copyOfRange(0, 8))!!
        val lsb = Int8Codec.decode(bytes.copyOfRange(8, 16))!!
        return kotlin.uuid.Uuid.fromLongs(msb, lsb)
    }
}


// java.time.Instant ↔ TIMESTAMPTZ
// Postgres epoch: microseconds since 2000-01-01 00:00:00 UTC
object InstantCodec : TypeCodec<java.time.Instant> {
    override val oid = PgOid.TIMESTAMPTZ
    private const val PG_EPOCH_MICROS = 946684800_000_000L
    override fun encode(value: java.time.Instant): ByteArray {
        val micros = value.epochSecond * 1_000_000L + value.nano / 1_000L - PG_EPOCH_MICROS
        return Int8Codec.encode(micros)
    }
    override fun decode(bytes: ByteArray?): java.time.Instant? {
        val micros = Int8Codec.decode(bytes) ?: return null
        val unix   = micros + PG_EPOCH_MICROS
        return java.time.Instant.ofEpochSecond(unix / 1_000_000L, (unix % 1_000_000L) * 1_000L)
    }
}

// java.time.LocalDate ↔ DATE
// Postgres DATE: days since 2000-01-01
object LocalDateCodec : TypeCodec<java.time.LocalDate> {
    override val oid = PgOid.DATE
    private val PG_EPOCH = java.time.LocalDate.of(2000, 1, 1)
    override fun encode(value: java.time.LocalDate): ByteArray =
        Int4Codec.encode(java.time.temporal.ChronoUnit.DAYS.between(PG_EPOCH, value).toInt())
    override fun decode(bytes: ByteArray?): java.time.LocalDate? =
        Int4Codec.decode(bytes)?.let { PG_EPOCH.plusDays(it.toLong()) }
}

// JSONB: Postgres prepends version byte 0x01
object JsonbCodec : TypeCodec<String> {
    override val oid = PgOid.JSONB
    override fun encode(value: String): ByteArray =
        byteArrayOf(1) + value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?): String? {
        if (bytes == null) return null
        return bytes.toString(Charsets.UTF_8).drop(1)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Array codec — encodes/decodes List<T> using the PostgreSQL binary array format
//
// Wire layout (1-D):
//   int32  ndim          = 1
//   int32  flags         = 0 (bit-0 would mean "contains nulls")
//   int32  element OID
//   int32  dim[0] length
//   int32  dim[0] lower bound = 1
//   For every element:
//     int32  byte length  (-1 = SQL NULL)
//     bytes  element data (absent when length == -1)
// ──────────────────────────────────────────────────────────────────────────────
class ArrayCodec<T : Any>(
    override val oid: Int,
    val elementCodec: TypeCodec<T>
) : TypeCodec<List<T>> {

    override fun encode(value: List<T>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)

        out.writeInt(1)                  // ndim
        out.writeInt(0)                  // flags
        out.writeInt(elementCodec.oid)   // element OID
        out.writeInt(value.size)         // dimension length
        out.writeInt(1)                  // lower bound

        for (element in value) {
            val bytes = elementCodec.encode(element)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        return buf.toByteArray()
    }

    override fun decode(bytes: ByteArray?): List<T>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))

        val ndim = inp.readInt()
        inp.readInt() // flags
        inp.readInt() // element OID

        if (ndim == 0) return emptyList()

        // Read all dimension descriptors to compute total element count
        var totalElements = 1
        repeat(ndim) {
            totalElements *= inp.readInt()
            inp.readInt() // lower bound
        }

        return List(totalElements) {
            val len = inp.readInt()
            if (len == -1) null  // SQL NULL element
            else {
                val elemBytes = ByteArray(len)
                inp.readFully(elemBytes)
                elementCodec.decode(elemBytes)
            }
        }.filterNotNull()
    }
}

// Pre-built array codec instances — mirror the scalar codecs above
val Int2ArrayCodec          = ArrayCodec(PgOid.INT2_ARRAY,        Int2Codec)
val Int4ArrayCodec          = ArrayCodec(PgOid.INT4_ARRAY,        Int4Codec)
val Int8ArrayCodec          = ArrayCodec(PgOid.INT8_ARRAY,        Int8Codec)
val Float4ArrayCodec        = ArrayCodec(PgOid.FLOAT4_ARRAY,      Float4Codec)
val Float8ArrayCodec        = ArrayCodec(PgOid.FLOAT8_ARRAY,      Float8Codec)
val TextArrayCodec          = ArrayCodec(PgOid.TEXT_ARRAY,        TextCodec)
val BoolArrayCodec          = ArrayCodec(PgOid.BOOL_ARRAY,        BoolCodec)
val TimestamptzArrayCodec   = ArrayCodec(PgOid.TIMESTAMPTZ_ARRAY, InstantCodec)

@OptIn(ExperimentalUuidApi::class)
val KotlinUuidArrayCodec    = ArrayCodec(PgOid.UUID_ARRAY,        KotlinUuidCodec)
