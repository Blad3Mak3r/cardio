package io.github.blad3mak3r.cardio.serialization

import io.github.blad3mak3r.cardio.protocol.Row
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * Entry point for decoding a [Row] into a [kotlinx.serialization] `@Serializable` class.
 *
 * Uses [CardioDecoder] internally.  Register custom serializers in [serializersModule] if
 * your data classes contain types that are not handled out of the box by Cardio codecs.
 *
 * ### Usage
 * ```kotlin
 * val user: User = CardioSerializationFormat.decodeFromRow(row)
 * // or with an explicit deserializer:
 * val user: User = CardioSerializationFormat.decodeFromRow(row, User.serializer())
 * ```
 */
object CardioSerializationFormat {
    val serializersModule: SerializersModule = SerializersModule {
        // Register contextual serializers here if needed, e.g.:
        // contextual(Uuid::class, UuidSerializer)
    }

    /**
     * Decodes [row] into an instance of [T] using the serializer inferred from the reified
     * type parameter.
     *
     * @param row The result row to decode.
     * @return A new instance of [T] with fields populated from [row].
     */
    inline fun <reified T> decodeFromRow(row: Row): T =
        serializer<T>().deserialize(
            CardioDecoder(row, serializersModule)
        )

    /**
     * Decodes [row] into an instance of [T] using an explicit [deserializer].
     *
     * @param row          The result row to decode.
     * @param deserializer The deserialization strategy to use.
     * @return A new instance of [T] with fields populated from [row].
     */
    fun <T> decodeFromRow(row: Row, deserializer: DeserializationStrategy<T>): T =
        deserializer.deserialize(CardioDecoder(row, serializersModule))
}