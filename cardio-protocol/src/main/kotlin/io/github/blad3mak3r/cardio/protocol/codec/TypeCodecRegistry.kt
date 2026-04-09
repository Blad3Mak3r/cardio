package io.github.blad3mak3r.cardio.protocol.codec

import kotlin.uuid.ExperimentalUuidApi

/**
 * Mutable registry that maps [TypeCodec] instances to PostgreSQL OIDs, enabling
 * codec lookup during both encoding (client → server) and decoding (server → client).
 *
 * Codecs are keyed by both the codec object itself and the [TypeCodec.oid] they declare.
 * Registering two codecs with the same OID replaces the earlier entry.
 *
 * The pre-populated [Default] singleton contains all built-in scalar and array codecs
 * and is used automatically by [io.github.blad3mak3r.cardio.protocol.connection.Connection].
 * Custom registries can be created by subclassing and calling [register] in an `init` block,
 * or by starting with [Default] and adding further codecs.
 */
open class TypeCodecRegistry {
    @PublishedApi internal val byCodec = mutableMapOf<TypeCodec<*>, CodecEntry<*>>()
    @PublishedApi internal val byOid = mutableMapOf<Int, CodecEntry<*>>()

    /**
     * Registers [codec] in this registry.
     *
     * If a codec with the same [TypeCodec.oid] was previously registered it is silently replaced.
     *
     * @param codec The codec to register.
     * @return This registry, to allow fluent chaining of multiple [register] calls.
     */
    fun <T : Any> register(codec: TypeCodec<T>): TypeCodecRegistry {
        val entry = CodecEntry(codec)
        byCodec[codec] = entry
        byOid[codec.oid] = entry

        return this
    }

    @PublishedApi @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> decodeByOid(oid: Int, bytes: ByteArray?): T? =
        (byOid[oid] as? CodecEntry<T>)?.decode(bytes)

    /**
     * Default [TypeCodecRegistry] pre-loaded with every built-in scalar and array codec.
     *
     * **Scalar codecs registered:** `Int2`, `Int4`, `Int8`, `Float4`, `Float8`, `Text`, `Varchar`,
     * `Bpchar`, `Bool`, `ByteArray`, `KotlinUuid`, `Instant`, `LocalDate`, `JSONB`, `JSON`,
     * `Numeric`, `Timestamp`, `Interval`, `Int4Range`, `Int8Range`, `NumRange`, `TsRange`,
     * `TsTzRange`, `DateRange`, `Inet`, `Cidr`, `MacAddr`, `MacAddr8`.
     *
     * **Array codecs registered:** `Int2[]`, `Int4[]`, `Int8[]`, `Float4[]`, `Float8[]`,
     * `Text[]`, `Varchar[]`, `Bool[]`, `KotlinUuid[]`, `Timestamp[]`, `Timestamptz[]`,
     * `Interval[]`, `Numeric[]`, `JSON[]`, `Inet[]`, `Cidr[]`, `MacAddr[]`, `MacAddr8[]`.
     */
    @OptIn(ExperimentalUuidApi::class)
    object Default : TypeCodecRegistry() {
        init {
            // Scalar codecs
            register(Int2Codec)
            register(Int4Codec)
            register(Int8Codec)
            register(Float4Codec)
            register(Float8Codec)
            register(TextCodec)
            register(VarcharCodec)
            register(BpcharCodec)
            register(BoolCodec)
            register(ByteArrayCodec)
            register(KotlinUuidCodec)
            register(InstantCodec)
            register(LocalDateCodec)
            register(JsonbCodec)
            register(JsonCodec)
            register(NumericCodec)
            register(TimestampCodec)
            register(IntervalCodec)
            register(Int4RangeCodec)
            register(Int8RangeCodec)
            register(NumRangeCodec)
            register(TsRangeCodec)
            register(TsTzRangeCodec)
            register(DateRangeCodec)
            register(InetCodec)
            register(CidrCodec)
            register(MacAddrCodec)
            register(MacAddr8Codec)

            // Array codecs
            register(Int2ArrayCodec)
            register(Int4ArrayCodec)
            register(Int8ArrayCodec)
            register(Float4ArrayCodec)
            register(Float8ArrayCodec)
            register(TextArrayCodec)
            register(VarcharArrayCodec)
            register(BoolArrayCodec)
            register(KotlinUuidArrayCodec)
            register(TimestampArrayCodec)
            register(TimestamptzArrayCodec)
            register(IntervalArrayCodec)
            register(NumericArrayCodec)
            register(JsonArrayCodec)
            register(InetArrayCodec)
            register(CidrArrayCodec)
            register(MacAddrArrayCodec)
            register(MacAddr8ArrayCodec)
        }
    }
}

