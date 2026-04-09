package io.github.blad3mak3r.cardio.protocol.codec

/**
 * Contract for encoding and decoding a single PostgreSQL data type.
 *
 * Implementations are singleton objects (or instances) that know:
 * - The PostgreSQL OID ([oid]) of the type they handle.
 * - How to convert a Kotlin value to the PostgreSQL binary wire format ([encode]).
 * - How to convert raw binary bytes from the server back into a Kotlin value ([decode]).
 *
 * Custom codecs can be created by implementing this interface and registering them
 * with [TypeCodecRegistry] via [TypeCodecRegistry.register].
 *
 * @param T The Kotlin type this codec maps to/from.
 */
interface TypeCodec<T : Any> {
    /**
     * PostgreSQL OID of the data type handled by this codec.
     * See [io.github.blad3mak3r.cardio.protocol.PgOid] for well-known OID constants.
     */
    val oid: Int

    /**
     * Encodes [value] into its PostgreSQL binary wire representation.
     *
     * @param value The Kotlin value to encode. Never `null`.
     * @return The binary-encoded bytes ready to be sent in a `Bind` message.
     */
    fun encode(value: T): ByteArray

    /**
     * Decodes [bytes] from the PostgreSQL binary wire format into a Kotlin value.
     *
     * @param bytes The raw bytes received from the server, or `null` for a SQL `NULL` value.
     * @return The decoded Kotlin value, or `null` if [bytes] is `null`.
     */
    fun decode(bytes: ByteArray?): T?
}