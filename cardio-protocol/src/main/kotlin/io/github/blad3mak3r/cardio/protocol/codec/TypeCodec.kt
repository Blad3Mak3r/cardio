package io.github.blad3mak3r.cardio.protocol.codec

interface TypeCodec<T : Any> {
    val oid: Int
    fun encode(value: T): ByteArray
    fun decode(bytes: ByteArray?): T?
}