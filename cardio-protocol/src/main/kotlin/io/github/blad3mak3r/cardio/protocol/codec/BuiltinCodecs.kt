package io.github.blad3mak3r.cardio.protocol.codec

import io.github.blad3mak3r.cardio.protocol.PgOid
import io.github.blad3mak3r.cardio.protocol.PgInterval
import io.github.blad3mak3r.cardio.protocol.PgRange
import io.github.blad3mak3r.cardio.protocol.PgInet
import kotlin.uuid.ExperimentalUuidApi

/** Codec for the PostgreSQL `SMALLINT` (`INT2`) type, mapped to [Short]. */
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

/** Codec for the PostgreSQL `INTEGER` (`INT4`) type, mapped to [Int]. */
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

/** Codec for the PostgreSQL `BIGINT` (`INT8`) type, mapped to [Long]. */
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

/** Codec for the PostgreSQL `REAL` (`FLOAT4`) type, mapped to [Float]. */
object Float4Codec : TypeCodec<Float> {
    override val oid = PgOid.FLOAT4
    override fun encode(value: Float)      = Int4Codec.encode(java.lang.Float.floatToIntBits(value))
    override fun decode(bytes: ByteArray?) = Int4Codec.decode(bytes)
        ?.let { java.lang.Float.intBitsToFloat(it) }
}

/** Codec for the PostgreSQL `DOUBLE PRECISION` (`FLOAT8`) type, mapped to [Double]. */
object Float8Codec : TypeCodec<Double> {
    override val oid = PgOid.FLOAT8
    override fun encode(value: Double)     = Int8Codec.encode(java.lang.Double.doubleToLongBits(value))
    override fun decode(bytes: ByteArray?) = Int8Codec.decode(bytes)
        ?.let { java.lang.Double.longBitsToDouble(it) }
}

/** Codec for the PostgreSQL `TEXT` type, mapped to [String] (UTF-8). */
object TextCodec : TypeCodec<String> {
    override val oid = PgOid.TEXT
    override fun encode(value: String)     = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?) = bytes?.toString(Charsets.UTF_8)
}

/** Codec for the PostgreSQL `CHARACTER VARYING` (`VARCHAR`) type, mapped to [String] (UTF-8). */
object VarcharCodec : TypeCodec<String> {
    override val oid = PgOid.VARCHAR
    override fun encode(value: String)     = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?) = bytes?.toString(Charsets.UTF_8)
}

/** Codec for the PostgreSQL `CHAR(n)` (`BPCHAR`) fixed-length blank-padded type, mapped to [String] (UTF-8). Trailing blank-padding sent by the server is preserved. */
object BpcharCodec : TypeCodec<String> {
    override val oid = PgOid.BPCHAR
    override fun encode(value: String)     = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?) = bytes?.toString(Charsets.UTF_8)
}

/** Codec for the PostgreSQL `BOOLEAN` type, mapped to [Boolean]. */
object BoolCodec : TypeCodec<Boolean> {
    override val oid = PgOid.BOOL
    override fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)
    override fun decode(bytes: ByteArray?)          = bytes?.let { it[0] != 0.toByte() }
}

/** Codec for the PostgreSQL `BYTEA` type, mapped to [ByteArray]. */
object ByteArrayCodec : TypeCodec<ByteArray> {
    override val oid = PgOid.BYTEA
    override fun encode(value: ByteArray)  = value
    override fun decode(bytes: ByteArray?) = bytes
}

/**
 * Codec for the PostgreSQL `UUID` type, mapped to [kotlin.uuid.Uuid].
 * Values are encoded as 16 bytes in big-endian (MSB first) binary format.
 */
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


/**
 * Codec for the PostgreSQL `TIMESTAMPTZ` type, mapped to [kotlin.time.Instant].
 * Values are encoded as microseconds since the PostgreSQL epoch (2000-01-01 00:00:00 UTC).
 */
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

/**
 * Codec for the PostgreSQL `DATE` type, mapped to [kotlinx.datetime.LocalDate].
 * Values are encoded as days since the PostgreSQL epoch (2000-01-01).
 */
object LocalDateCodec : TypeCodec<kotlinx.datetime.LocalDate> {
    override val oid = PgOid.DATE
    private const val PG_EPOCH_DAYS = 10957L // days from 1970-01-01 to 2000-01-01
    override fun encode(value: kotlinx.datetime.LocalDate): ByteArray =
        Int4Codec.encode((value.toEpochDays() - PG_EPOCH_DAYS).toInt())
    override fun decode(bytes: ByteArray?): kotlinx.datetime.LocalDate? =
        Int4Codec.decode(bytes)?.let { kotlinx.datetime.LocalDate.fromEpochDays(it + PG_EPOCH_DAYS) }
}

/**
 * Codec for the PostgreSQL `JSONB` type, mapped to [String] (raw JSON text).
 * The JSONB binary wire format prepends a version byte (`0x01`) that is stripped on decode.
 */
object JsonbCodec : TypeCodec<String> {
    override val oid = PgOid.JSONB
    override fun encode(value: String): ByteArray =
        byteArrayOf(1) + value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?): String? {
        if (bytes == null) return null
        return bytes.toString(Charsets.UTF_8).drop(1)
    }
}

/** Codec for the PostgreSQL `JSON` type, mapped to [String] (raw JSON text, UTF-8, no version byte). */
object JsonCodec : TypeCodec<String> {
    override val oid = PgOid.JSON
    override fun encode(value: String): ByteArray =
        value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray?): String? =
        bytes?.toString(Charsets.UTF_8)
}

/**
 * Codec for the PostgreSQL `INET` type, mapped to [PgInet] (host address + netmask).
 * The binary format encodes: address family (2=IPv4, 3=IPv6), netmask bits,
 * `is_cidr` flag (always `0` for INET), address length, and the address bytes.
 */
object InetCodec : TypeCodec<PgInet> {
    override val oid = PgOid.INET
    
    override fun encode(value: PgInet): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        val family: Byte = if (value.isIPv4) 2 else 3
        val addrBytes = value.address.address
        
        out.writeByte(family.toInt())
        out.writeByte(value.netmask)
        out.writeByte(0) // is_cidr = 0 (inet, not cidr)
        out.writeByte(addrBytes.size)
        out.write(addrBytes)
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgInet? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val family = inp.readByte().toInt()
        val netmask = inp.readByte().toInt()
        val isCidr = inp.readByte().toInt()
        val addrLen = inp.readByte().toInt()
        
        val addrBytes = ByteArray(addrLen)
        inp.readFully(addrBytes)
        
        val address = java.net.InetAddress.getByAddress(addrBytes)
        return PgInet(address, netmask)
    }
}

/**
 * Codec for the PostgreSQL `CIDR` type, mapped to [PgInet] (network address + netmask).
 * Uses the same binary format as [InetCodec] but with the `is_cidr` flag set to `1`.
 */
object CidrCodec : TypeCodec<PgInet> {
    override val oid = PgOid.CIDR
    
    override fun encode(value: PgInet): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        val family: Byte = if (value.isIPv4) 2 else 3
        val addrBytes = value.address.address
        
        out.writeByte(family.toInt())
        out.writeByte(value.netmask)
        out.writeByte(1) // is_cidr = 1 (network address)
        out.writeByte(addrBytes.size)
        out.write(addrBytes)
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgInet? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val family = inp.readByte().toInt()
        val netmask = inp.readByte().toInt()
        val isCidr = inp.readByte().toInt()
        val addrLen = inp.readByte().toInt()
        
        val addrBytes = ByteArray(addrLen)
        inp.readFully(addrBytes)
        
        val address = java.net.InetAddress.getByAddress(addrBytes)
        return PgInet(address, netmask)
    }
}

/**
 * Codec for the PostgreSQL `MACADDR` type, mapped to [String] in `XX:XX:XX:XX:XX:XX` format.
 * Accepts colons, hyphens, or dots as separators during encoding; always outputs colon-separated hex on decode.
 */
object MacAddrCodec : TypeCodec<String> {
    override val oid = PgOid.MACADDR
    
    override fun encode(value: String): ByteArray {
        // Parse MAC address (supports various formats)
        val cleaned = value.replace(":", "").replace("-", "").replace(".", "")
        require(cleaned.length == 12) { "Invalid MAC address format: $value" }
        
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    
    override fun decode(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != 6) return null
        return bytes.joinToString(":") { "%02x".format(it) }
    }
}

/**
 * Codec for the PostgreSQL `MACADDR8` (EUI-64) type, mapped to [String] in `XX:XX:XX:XX:XX:XX:XX:XX` format.
 * Accepts colons, hyphens, or dots as separators during encoding; always outputs colon-separated hex on decode.
 */
object MacAddr8Codec : TypeCodec<String> {
    override val oid = PgOid.MACADDR8
    
    override fun encode(value: String): ByteArray {
        val cleaned = value.replace(":", "").replace("-", "").replace(".", "")
        require(cleaned.length == 16) { "Invalid MACADDR8 format: $value" }
        
        return ByteArray(8) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    
    override fun decode(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != 8) return null
        return bytes.joinToString(":") { "%02x".format(it) }
    }
}

/**
 * Codec for the PostgreSQL `NUMERIC`/`DECIMAL` type, mapped to [java.math.BigDecimal].
 * Uses the PostgreSQL binary numeric format (base-10000 digit groups with weight, sign and scale).
 * PostgreSQL `NaN` is decoded as `null` because [java.math.BigDecimal] has no NaN representation.
 */
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

/**
 * Codec for the PostgreSQL `TIMESTAMP` (without timezone) type, mapped to [kotlinx.datetime.LocalDateTime].
 * Values are encoded as microseconds since the PostgreSQL epoch (2000-01-01 00:00:00, no timezone).
 * The [kotlinx.datetime.LocalDateTime] is treated as calendar fields with no timezone conversion.
 */
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

/**
 * Codec for the PostgreSQL `INTERVAL` type, mapped to [PgInterval].
 * The binary format encodes 8 bytes of microseconds (time portion), 4 bytes of days,
 * and 4 bytes of months, all in big-endian order.
 */
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

/** Codec for the PostgreSQL `INT4RANGE` type, mapped to [PgRange]`<`[Int]`>`. */
object Int4RangeCodec : TypeCodec<PgRange<Int>> {
    override val oid = PgOid.INT4RANGE
    
    private const val LOWER_INCLUSIVE = 0x01
    private const val UPPER_INCLUSIVE = 0x02
    private const val LOWER_UNBOUNDED = 0x04
    private const val UPPER_UNBOUNDED = 0x08
    private const val EMPTY = 0x10
    
    override fun encode(value: PgRange<Int>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Build flags byte
        var flags = 0
        if (value.empty) {
            flags = flags or EMPTY
            out.writeByte(flags)
            return buf.toByteArray()
        }
        
        if (value.lowerInclusive) flags = flags or LOWER_INCLUSIVE
        if (value.upperInclusive) flags = flags or UPPER_INCLUSIVE
        if (value.lower == null) flags = flags or LOWER_UNBOUNDED
        if (value.upper == null) flags = flags or UPPER_UNBOUNDED
        
        out.writeByte(flags)
        
        // Write bounds if present
        if (value.lower != null) {
            out.writeInt(value.lower)
        }
        if (value.upper != null) {
            out.writeInt(value.upper)
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgRange<Int>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val flags = inp.readByte().toInt()
        
        if ((flags and EMPTY) != 0) {
            return PgRange.empty()
        }
        
        val lowerInclusive = (flags and LOWER_INCLUSIVE) != 0
        val upperInclusive = (flags and UPPER_INCLUSIVE) != 0
        val lowerUnbounded = (flags and LOWER_UNBOUNDED) != 0
        val upperUnbounded = (flags and UPPER_UNBOUNDED) != 0
        
        val lower = if (lowerUnbounded) null else inp.readInt()
        val upper = if (upperUnbounded) null else inp.readInt()
        
        return PgRange(lower, upper, lowerInclusive, upperInclusive)
    }
}

/** Codec for the PostgreSQL `INT8RANGE` type, mapped to [PgRange]`<`[Long]`>`. */
object Int8RangeCodec : TypeCodec<PgRange<Long>> {
    override val oid = PgOid.INT8RANGE
    
    private const val LOWER_INCLUSIVE = 0x01
    private const val UPPER_INCLUSIVE = 0x02
    private const val LOWER_UNBOUNDED = 0x04
    private const val UPPER_UNBOUNDED = 0x08
    private const val EMPTY = 0x10
    
    override fun encode(value: PgRange<Long>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Build flags byte
        var flags = 0
        if (value.empty) {
            flags = flags or EMPTY
            out.writeByte(flags)
            return buf.toByteArray()
        }
        
        if (value.lowerInclusive) flags = flags or LOWER_INCLUSIVE
        if (value.upperInclusive) flags = flags or UPPER_INCLUSIVE
        if (value.lower == null) flags = flags or LOWER_UNBOUNDED
        if (value.upper == null) flags = flags or UPPER_UNBOUNDED
        
        out.writeByte(flags)
        
        // Write bounds if present
        if (value.lower != null) {
            out.writeLong(value.lower)
        }
        if (value.upper != null) {
            out.writeLong(value.upper)
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgRange<Long>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val flags = inp.readByte().toInt()
        
        if ((flags and EMPTY) != 0) {
            return PgRange.empty()
        }
        
        val lowerInclusive = (flags and LOWER_INCLUSIVE) != 0
        val upperInclusive = (flags and UPPER_INCLUSIVE) != 0
        val lowerUnbounded = (flags and LOWER_UNBOUNDED) != 0
        val upperUnbounded = (flags and UPPER_UNBOUNDED) != 0
        
        val lower = if (lowerUnbounded) null else inp.readLong()
        val upper = if (upperUnbounded) null else inp.readLong()
        
        return PgRange(lower, upper, lowerInclusive, upperInclusive)
    }
}

/** Codec for the PostgreSQL `NUMRANGE` type, mapped to [PgRange]`<`[java.math.BigDecimal]`>`. */
object NumRangeCodec : TypeCodec<PgRange<java.math.BigDecimal>> {
    override val oid = PgOid.NUMRANGE
    
    private const val LOWER_INCLUSIVE = 0x01
    private const val UPPER_INCLUSIVE = 0x02
    private const val LOWER_UNBOUNDED = 0x04
    private const val UPPER_UNBOUNDED = 0x08
    private const val EMPTY = 0x10
    
    override fun encode(value: PgRange<java.math.BigDecimal>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Build flags byte
        var flags = 0
        if (value.empty) {
            flags = flags or EMPTY
            out.writeByte(flags)
            return buf.toByteArray()
        }
        
        if (value.lowerInclusive) flags = flags or LOWER_INCLUSIVE
        if (value.upperInclusive) flags = flags or UPPER_INCLUSIVE
        if (value.lower == null) flags = flags or LOWER_UNBOUNDED
        if (value.upper == null) flags = flags or UPPER_UNBOUNDED
        
        out.writeByte(flags)
        
        // Write bounds if present (each bound is preceded by its length)
        if (value.lower != null) {
            val lowerBytes = NumericCodec.encode(value.lower)
            out.writeInt(lowerBytes.size)
            out.write(lowerBytes)
        }
        if (value.upper != null) {
            val upperBytes = NumericCodec.encode(value.upper)
            out.writeInt(upperBytes.size)
            out.write(upperBytes)
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgRange<java.math.BigDecimal>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val flags = inp.readByte().toInt()
        
        if ((flags and EMPTY) != 0) {
            return PgRange.empty()
        }
        
        val lowerInclusive = (flags and LOWER_INCLUSIVE) != 0
        val upperInclusive = (flags and UPPER_INCLUSIVE) != 0
        val lowerUnbounded = (flags and LOWER_UNBOUNDED) != 0
        val upperUnbounded = (flags and UPPER_UNBOUNDED) != 0
        
        val lower = if (lowerUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            NumericCodec.decode(boundBytes)
        }
        
        val upper = if (upperUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            NumericCodec.decode(boundBytes)
        }
        
        return PgRange(lower, upper, lowerInclusive, upperInclusive)
    }
}

/** Codec for the PostgreSQL `TSRANGE` type, mapped to [PgRange]`<`[kotlinx.datetime.LocalDateTime]`>`. */
object TsRangeCodec : TypeCodec<PgRange<kotlinx.datetime.LocalDateTime>> {
    override val oid = PgOid.TSRANGE
    
    private const val LOWER_INCLUSIVE = 0x01
    private const val UPPER_INCLUSIVE = 0x02
    private const val LOWER_UNBOUNDED = 0x04
    private const val UPPER_UNBOUNDED = 0x08
    private const val EMPTY = 0x10
    
    override fun encode(value: PgRange<kotlinx.datetime.LocalDateTime>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Build flags byte
        var flags = 0
        if (value.empty) {
            flags = flags or EMPTY
            out.writeByte(flags)
            return buf.toByteArray()
        }
        
        if (value.lowerInclusive) flags = flags or LOWER_INCLUSIVE
        if (value.upperInclusive) flags = flags or UPPER_INCLUSIVE
        if (value.lower == null) flags = flags or LOWER_UNBOUNDED
        if (value.upper == null) flags = flags or UPPER_UNBOUNDED
        
        out.writeByte(flags)
        
        // Write bounds if present (each bound is preceded by its length = 8)
        if (value.lower != null) {
            out.writeInt(8)  // timestamp is always 8 bytes
            out.write(TimestampCodec.encode(value.lower))
        }
        if (value.upper != null) {
            out.writeInt(8)
            out.write(TimestampCodec.encode(value.upper))
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgRange<kotlinx.datetime.LocalDateTime>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val flags = inp.readByte().toInt()
        
        if ((flags and EMPTY) != 0) {
            return PgRange.empty()
        }
        
        val lowerInclusive = (flags and LOWER_INCLUSIVE) != 0
        val upperInclusive = (flags and UPPER_INCLUSIVE) != 0
        val lowerUnbounded = (flags and LOWER_UNBOUNDED) != 0
        val upperUnbounded = (flags and UPPER_UNBOUNDED) != 0
        
        val lower = if (lowerUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            TimestampCodec.decode(boundBytes)
        }
        
        val upper = if (upperUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            TimestampCodec.decode(boundBytes)
        }
        
        return PgRange(lower, upper, lowerInclusive, upperInclusive)
    }
}

/** Codec for the PostgreSQL `TSTZRANGE` type, mapped to [PgRange]`<`[kotlin.time.Instant]`>`. */
object TsTzRangeCodec : TypeCodec<PgRange<kotlin.time.Instant>> {
    override val oid = PgOid.TSTZRANGE
    
    private const val LOWER_INCLUSIVE = 0x01
    private const val UPPER_INCLUSIVE = 0x02
    private const val LOWER_UNBOUNDED = 0x04
    private const val UPPER_UNBOUNDED = 0x08
    private const val EMPTY = 0x10
    
    override fun encode(value: PgRange<kotlin.time.Instant>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Build flags byte
        var flags = 0
        if (value.empty) {
            flags = flags or EMPTY
            out.writeByte(flags)
            return buf.toByteArray()
        }
        
        if (value.lowerInclusive) flags = flags or LOWER_INCLUSIVE
        if (value.upperInclusive) flags = flags or UPPER_INCLUSIVE
        if (value.lower == null) flags = flags or LOWER_UNBOUNDED
        if (value.upper == null) flags = flags or UPPER_UNBOUNDED
        
        out.writeByte(flags)
        
        // Write bounds if present (each bound is preceded by its length = 8)
        if (value.lower != null) {
            out.writeInt(8)  // timestamptz is always 8 bytes
            out.write(InstantCodec.encode(value.lower))
        }
        if (value.upper != null) {
            out.writeInt(8)
            out.write(InstantCodec.encode(value.upper))
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgRange<kotlin.time.Instant>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val flags = inp.readByte().toInt()
        
        if ((flags and EMPTY) != 0) {
            return PgRange.empty()
        }
        
        val lowerInclusive = (flags and LOWER_INCLUSIVE) != 0
        val upperInclusive = (flags and UPPER_INCLUSIVE) != 0
        val lowerUnbounded = (flags and LOWER_UNBOUNDED) != 0
        val upperUnbounded = (flags and UPPER_UNBOUNDED) != 0
        
        val lower = if (lowerUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            InstantCodec.decode(boundBytes)
        }
        
        val upper = if (upperUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            InstantCodec.decode(boundBytes)
        }
        
        return PgRange(lower, upper, lowerInclusive, upperInclusive)
    }
}

/** Codec for the PostgreSQL `DATERANGE` type, mapped to [PgRange]`<`[kotlinx.datetime.LocalDate]`>`. */
object DateRangeCodec : TypeCodec<PgRange<kotlinx.datetime.LocalDate>> {
    override val oid = PgOid.DATERANGE
    
    private const val LOWER_INCLUSIVE = 0x01
    private const val UPPER_INCLUSIVE = 0x02
    private const val LOWER_UNBOUNDED = 0x04
    private const val UPPER_UNBOUNDED = 0x08
    private const val EMPTY = 0x10
    
    override fun encode(value: PgRange<kotlinx.datetime.LocalDate>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        
        // Build flags byte
        var flags = 0
        if (value.empty) {
            flags = flags or EMPTY
            out.writeByte(flags)
            return buf.toByteArray()
        }
        
        if (value.lowerInclusive) flags = flags or LOWER_INCLUSIVE
        if (value.upperInclusive) flags = flags or UPPER_INCLUSIVE
        if (value.lower == null) flags = flags or LOWER_UNBOUNDED
        if (value.upper == null) flags = flags or UPPER_UNBOUNDED
        
        out.writeByte(flags)
        
        // Write bounds if present (each bound is preceded by its length = 4)
        if (value.lower != null) {
            out.writeInt(4)  // date is always 4 bytes
            out.write(LocalDateCodec.encode(value.lower))
        }
        if (value.upper != null) {
            out.writeInt(4)
            out.write(LocalDateCodec.encode(value.upper))
        }
        
        return buf.toByteArray()
    }
    
    override fun decode(bytes: ByteArray?): PgRange<kotlinx.datetime.LocalDate>? {
        if (bytes == null) return null
        val inp = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        
        val flags = inp.readByte().toInt()
        
        if ((flags and EMPTY) != 0) {
            return PgRange.empty()
        }
        
        val lowerInclusive = (flags and LOWER_INCLUSIVE) != 0
        val upperInclusive = (flags and UPPER_INCLUSIVE) != 0
        val lowerUnbounded = (flags and LOWER_UNBOUNDED) != 0
        val upperUnbounded = (flags and UPPER_UNBOUNDED) != 0
        
        val lower = if (lowerUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            LocalDateCodec.decode(boundBytes)
        }
        
        val upper = if (upperUnbounded) null else {
            val len = inp.readInt()
            val boundBytes = ByteArray(len)
            inp.readFully(boundBytes)
            LocalDateCodec.decode(boundBytes)
        }
        
        return PgRange(lower, upper, lowerInclusive, upperInclusive)
    }
}


/**
 * Generic [TypeCodec] for PostgreSQL one-dimensional array types, mapping to `List<T?>`.
 *
 * The PostgreSQL binary array wire format encodes:
 * - 4 bytes: number of dimensions (`1` for all arrays produced by this codec)
 * - 4 bytes: flags (`0` — null-bitmap not sent)
 * - 4 bytes: element OID
 * - 4 bytes: dimension length
 * - 4 bytes: dimension lower bound (always `1`)
 * - For each element: 4 bytes length (`-1` for SQL NULL) followed by the element bytes
 *
 * SQL NULL elements are preserved as `null` at their original position in the decoded list.
 *
 * For the common scalar types, pre-built instances like [Int4ArrayCodec], [TextArrayCodec],
 * etc. are provided — there is no need to instantiate [ArrayCodec] directly for those types.
 *
 * @param T           Kotlin element type.
 * @param oid         PostgreSQL OID of the array type (e.g. [PgOid.INT4_ARRAY]).
 * @param elementCodec Codec used to encode and decode individual array elements.
 */
class ArrayCodec<T : Any>(
    override val oid: Int,
    /** Codec used to encode and decode individual elements of the array. */
    val elementCodec: TypeCodec<T>
) : TypeCodec<List<T?>> {

    override fun encode(value: List<T?>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)

        out.writeInt(1)                  // ndim
        out.writeInt(0)                  // flags
        out.writeInt(elementCodec.oid)   // element OID
        out.writeInt(value.size)         // dimension length
        out.writeInt(1)                  // lower bound

        for (element in value) {
            if (element == null) {
                out.writeInt(-1)
            } else {
                val bytes = elementCodec.encode(element)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }

        return buf.toByteArray()
    }

    override fun decode(bytes: ByteArray?): List<T?>? {
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
        }
    }
}

// Pre-built array codec instances — one for each built-in scalar codec.
// Use these directly as query parameters or pass them to Param(list, codec).
/** Pre-built [ArrayCodec] for the PostgreSQL `SMALLINT[]` (`INT2[]`) type. */
val Int2ArrayCodec          = ArrayCodec(PgOid.INT2_ARRAY,        Int2Codec)
/** Pre-built [ArrayCodec] for the PostgreSQL `INTEGER[]` (`INT4[]`) type. */
val Int4ArrayCodec          = ArrayCodec(PgOid.INT4_ARRAY,        Int4Codec)
/** Pre-built [ArrayCodec] for the PostgreSQL `BIGINT[]` (`INT8[]`) type. */
val Int8ArrayCodec          = ArrayCodec(PgOid.INT8_ARRAY,        Int8Codec)
/** Pre-built [ArrayCodec] for the PostgreSQL `REAL[]` (`FLOAT4[]`) type. */
val Float4ArrayCodec        = ArrayCodec(PgOid.FLOAT4_ARRAY,      Float4Codec)
/** Pre-built [ArrayCodec] for the PostgreSQL `DOUBLE PRECISION[]` (`FLOAT8[]`) type. */
val Float8ArrayCodec        = ArrayCodec(PgOid.FLOAT8_ARRAY,      Float8Codec)
/** Pre-built [ArrayCodec] for the PostgreSQL `TEXT[]` type. */
val TextArrayCodec          = ArrayCodec(PgOid.TEXT_ARRAY,        TextCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `VARCHAR[]` type. */
val VarcharArrayCodec       = ArrayCodec(PgOid.VARCHAR_ARRAY,     VarcharCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `BOOLEAN[]` type. */
val BoolArrayCodec          = ArrayCodec(PgOid.BOOL_ARRAY,        BoolCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `TIMESTAMP[]` (without timezone) type. */
val TimestampArrayCodec     = ArrayCodec(PgOid.TIMESTAMP_ARRAY,   TimestampCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `TIMESTAMPTZ[]` type. */
val TimestamptzArrayCodec   = ArrayCodec(PgOid.TIMESTAMPTZ_ARRAY, InstantCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `INTERVAL[]` type. */
val IntervalArrayCodec      = ArrayCodec(PgOid.INTERVAL_ARRAY,    IntervalCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `NUMERIC[]` type. */
val NumericArrayCodec       = ArrayCodec(PgOid.NUMERIC_ARRAY,     NumericCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `JSON[]` type. */
val JsonArrayCodec          = ArrayCodec(PgOid.JSON_ARRAY,        JsonCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `INET[]` type. */
val InetArrayCodec          = ArrayCodec(PgOid.INET_ARRAY,        InetCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `CIDR[]` type. */
val CidrArrayCodec          = ArrayCodec(PgOid.CIDR_ARRAY,        CidrCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `MACADDR[]` type. */
val MacAddrArrayCodec       = ArrayCodec(PgOid.MACADDR_ARRAY,     MacAddrCodec)
/** Pre-built [ArrayCodec] for the PostgreSQL `MACADDR8[]` (EUI-64) type. */
val MacAddr8ArrayCodec      = ArrayCodec(PgOid.MACADDR8_ARRAY,    MacAddr8Codec)

/** Pre-built [ArrayCodec] for the PostgreSQL `UUID[]` type, using [kotlin.uuid.Uuid] elements. */
@OptIn(ExperimentalUuidApi::class)
val KotlinUuidArrayCodec    = ArrayCodec(PgOid.UUID_ARRAY,        KotlinUuidCodec)

