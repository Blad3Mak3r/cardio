package io.github.blad3mak3r.cardio.protocol.codec

import io.github.blad3mak3r.cardio.protocol.PgOid
import io.github.blad3mak3r.cardio.protocol.PgInterval
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

// CHARACTER VARYING / VARCHAR
object VarcharCodec : TypeCodec<String> {
    override val oid = PgOid.VARCHAR
    override fun encode(value: String)     = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?) = bytes?.toString(Charsets.UTF_8)
}

// CHAR(n) / BPCHAR — fixed-length blank-padded character type
object BpcharCodec : TypeCodec<String> {
    override val oid = PgOid.BPCHAR
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


// kotlin.Instant ↔ TIMESTAMPTZ
// Postgres epoch: microseconds since 2000-01-01 00:00:00 UTC
object InstantCodec : TypeCodec<kotlin.time.Instant> {
    override val oid = PgOid.TIMESTAMPTZ
    private const val PG_EPOCH_MICROS = 946684800_000_000L
    override fun encode(value: kotlin.time.Instant): ByteArray {
        val micros = value.epochSeconds * 1_000_000L + value.nanosecondsOfSecond / 1_000L - PG_EPOCH_MICROS
        return Int8Codec.encode(micros)
    }
    override fun decode(bytes: ByteArray?): kotlin.time.Instant? {
        val micros = Int8Codec.decode(bytes) ?: return null
        val unix   = micros + PG_EPOCH_MICROS
        return kotlin.time.Instant.fromEpochSeconds(unix / 1_000_000L, (unix % 1_000_000L) * 1_000L)
    }
}

// kotlinx.datetime.LocalDate ↔ DATE
// Postgres DATE: days since 2000-01-01; kotlinx.datetime epoch: days since 1970-01-01
object LocalDateCodec : TypeCodec<kotlinx.datetime.LocalDate> {
    override val oid = PgOid.DATE
    private const val PG_EPOCH_DAYS = 10957L // days from 1970-01-01 to 2000-01-01
    override fun encode(value: kotlinx.datetime.LocalDate): ByteArray =
        Int4Codec.encode((value.toEpochDays() - PG_EPOCH_DAYS).toInt())
    override fun decode(bytes: ByteArray?): kotlinx.datetime.LocalDate? =
        Int4Codec.decode(bytes)?.let { kotlinx.datetime.LocalDate.fromEpochDays(it + PG_EPOCH_DAYS) }
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

// NUMERIC / DECIMAL → java.math.BigDecimal
// PostgreSQL binary format:
//   int16 ndigits (number of base-10000 digits)
//   int16 weight  (power of 10000 for first digit)
//   int16 sign    (0x0000 = positive, 0x4000 = negative, 0xC000 = NaN)
//   int16 dscale  (number of decimal digits after decimal point)
//   int16[] digits (base-10000 digits, most significant first)
object NumericCodec : TypeCodec<java.math.BigDecimal> {
    override val oid = PgOid.NUMERIC
    
    override fun encode(value: java.math.BigDecimal): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Handle zero specially
        if (value.compareTo(java.math.BigDecimal.ZERO) == 0) {
            out.writeShort(0) // ndigits
            out.writeShort(0) // weight
            out.writeShort(0) // sign (positive)
            out.writeShort(0) // dscale
            return buf.toByteArray()
        }
        
        val sign = when {
            value.signum() < 0 -> 0x4000
            else -> 0x0000
        }
        
        val absValue = value.abs()
        val scale = value.scale()
        
        // Convert to unscaled string and split into base-10000 digits
        val unscaledStr = absValue.unscaledValue().toString()
        val totalDigits = unscaledStr.length
        
        // Calculate weight (position of first digit group relative to decimal point)
        val firstDigitPos = totalDigits - scale
        val weight = (firstDigitPos - 1) / 4
        
        // Pad to make length divisible by 4
        val padLeft = ((4 - ((firstDigitPos - 1) % 4) - 1) % 4)
        val paddedStr = "0".repeat(padLeft) + unscaledStr
        
        // Split into groups of 4 decimal digits (base-10000)
        val digitGroups = mutableListOf<Short>()
        var i = 0
        while (i < paddedStr.length) {
            val end = minOf(i + 4, paddedStr.length)
            val group = paddedStr.substring(i, end).toShort()
            digitGroups.add(group)
            i += 4
        }
        
        // Remove trailing zeros
        while (digitGroups.isNotEmpty() && digitGroups.last() == 0.toShort()) {
            digitGroups.removeLast()
        }
        
        out.writeShort(digitGroups.size)
        out.writeShort(weight)
        out.writeShort(sign)
        out.writeShort(scale)
        
        for (digit in digitGroups) {
            out.writeShort(digit.toInt())
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): java.math.BigDecimal? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val ndigits = inp.readShort().toInt()
        val weight = inp.readShort().toInt()
        val sign = inp.readShort().toInt()
        val dscale = inp.readShort().toInt()
        
        if (ndigits == 0) return java.math.BigDecimal.ZERO
        if (sign == 0xC000) return null // NaN not supported in BigDecimal
        
        val digits = ShortArray(ndigits) { inp.readShort() }
        
        // Build unscaled value string
        val sb = StringBuilder()
        for (i in digits.indices) {
            val digit = digits[i].toInt()
            if (i == 0) {
                sb.append(digit)
            } else {
                sb.append(digit.toString().padStart(4, '0'))
            }
        }
        
        val unscaledValue = java.math.BigInteger(sb.toString())
        val result = java.math.BigDecimal(unscaledValue, dscale)
        
        return if (sign == 0x4000) result.negate() else result
    }
}

// TIMESTAMP (without timezone) → kotlinx.datetime.LocalDateTime
// PostgreSQL format: microseconds since 2000-01-01 00:00:00 (no timezone)
// LocalDateTime is treated as if it were in UTC for encoding/decoding purposes
object TimestampCodec : TypeCodec<kotlinx.datetime.LocalDateTime> {
    override val oid = PgOid.TIMESTAMP
    private const val PG_EPOCH_MICROS = 946684800_000_000L // microseconds from Unix epoch to 2000-01-01 00:00:00 UTC
    
    override fun encode(value: kotlinx.datetime.LocalDateTime): ByteArray {
        // Convert LocalDateTime components to microseconds since Unix epoch
        val date = value.date
        val time = value.time
        
        // Days since Unix epoch (1970-01-01)
        val days = date.toEpochDays().toLong()
        
        // Microseconds within the day
        val timeOfDayMicros = time.toSecondOfDay().toLong() * 1_000_000L + time.nanosecond / 1_000L
        
        // Total microseconds since Unix epoch
        val unixMicros = days * 86400_000_000L + timeOfDayMicros
        
        // Subtract PG epoch to get microseconds since 2000-01-01
        val pgMicros = unixMicros - PG_EPOCH_MICROS
        
        return Int8Codec.encode(pgMicros)
    }
    
    override fun decode(bytes: ByteArray?): kotlinx.datetime.LocalDateTime? {
        val pgMicros = Int8Codec.decode(bytes) ?: return null
        
        // Convert to microseconds since Unix epoch
        val unixMicros = pgMicros + PG_EPOCH_MICROS
        
        // Extract days and time components
        val days = (unixMicros / 86400_000_000L).toInt()
        val timeOfDayMicros = unixMicros % 86400_000_000L
        
        val date = kotlinx.datetime.LocalDate.fromEpochDays(days)
        
        val secondOfDay = (timeOfDayMicros / 1_000_000L).toInt()
        val nanos = ((timeOfDayMicros % 1_000_000L) * 1_000L).toInt()
        
        val hour = secondOfDay / 3600
        val minute = (secondOfDay % 3600) / 60
        val second = secondOfDay % 60
        
        return kotlinx.datetime.LocalDateTime(
            date.year, date.month, date.day,
            hour, minute, second, nanos
        )
    }
}

// INTERVAL → PgInterval
// PostgreSQL binary format:
//   int64 microseconds (time portion)
//   int32 days
//   int32 months
object IntervalCodec : TypeCodec<PgInterval> {
    override val oid = PgOid.INTERVAL
    
    override fun encode(value: PgInterval): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        out.writeLong(value.microseconds)
        out.writeInt(value.days)
        out.writeInt(value.months)
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgInterval? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val microseconds = inp.readLong()
        val days = inp.readInt()
        val months = inp.readInt()
        
        return PgInterval(months, days, microseconds)
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
val VarcharArrayCodec       = ArrayCodec(PgOid.VARCHAR_ARRAY,     VarcharCodec)
val BoolArrayCodec          = ArrayCodec(PgOid.BOOL_ARRAY,        BoolCodec)
val TimestampArrayCodec     = ArrayCodec(PgOid.TIMESTAMP_ARRAY,   TimestampCodec)
val TimestamptzArrayCodec   = ArrayCodec(PgOid.TIMESTAMPTZ_ARRAY, InstantCodec)
val IntervalArrayCodec      = ArrayCodec(PgOid.INTERVAL_ARRAY,    IntervalCodec)
val NumericArrayCodec       = ArrayCodec(PgOid.NUMERIC_ARRAY,     NumericCodec)

@OptIn(ExperimentalUuidApi::class)
val KotlinUuidArrayCodec    = ArrayCodec(PgOid.UUID_ARRAY,        KotlinUuidCodec)

