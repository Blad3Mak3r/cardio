package io.github.blad3mak3r.cardio.protocol.codec

@PublishedApi
internal class CodecEntry<T : Any>(
    val codec: TypeCodec<T>
) {
    @Suppress("UNCHECKED_CAST")
    fun encodeAny(value: T): ByteArray = codec.encode(value as T)

    fun decode(bytes: ByteArray?): T? = codec.decode(bytes)
}