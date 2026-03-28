package io.github.blad3mak3r.cardio.protocol.codec

import kotlin.uuid.ExperimentalUuidApi

open class TypeCodecRegistry {
    @PublishedApi internal val byCodec = mutableMapOf<TypeCodec<*>, CodecEntry<*>>()
    @PublishedApi internal val byOid = mutableMapOf<Int, CodecEntry<*>>()

    fun <T : Any> register(codec: TypeCodec<T>): TypeCodecRegistry {
        val entry = CodecEntry(codec)
        byCodec[codec] = entry
        byOid[codec.oid] = entry

        return this
    }

    @PublishedApi @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> decodeByOid(oid: Int, bytes: ByteArray?): T? =
        (byOid[oid] as? CodecEntry<T>)?.decode(bytes)

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
            register(NumericCodec)
            register(TimestampCodec)
            register(IntervalCodec)
            register(Int4RangeCodec)
            register(Int8RangeCodec)
            register(NumRangeCodec)
            register(TsRangeCodec)
            register(TsTzRangeCodec)
            register(DateRangeCodec)

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
        }
    }
}
