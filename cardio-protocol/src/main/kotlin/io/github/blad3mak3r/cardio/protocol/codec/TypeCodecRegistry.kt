package io.github.blad3mak3r.cardio.protocol.codec

class TypeCodecRegistry {
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

    companion object {
        fun default(): TypeCodecRegistry = TypeCodecRegistry()
            .register(Int2Codec).register(Int4Codec).register(Int8Codec)
            .register(Float4Codec).register(Float8Codec)
            .register(TextCodec).register(BoolCodec).register(ByteArrayCodec)
            .register(UuidCodec).register(InstantCodec)
            .register(LocalDateCodec).register(JsonbCodec)
    }
}