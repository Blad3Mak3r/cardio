package io.github.blad3mak3r.cardio.serialization

import io.github.blad3mak3r.cardio.protocol.Row
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

object CardioSerializationFormat {
    val serializersModule: SerializersModule = SerializersModule {
        // Aquí el usuario puede registrar serializers custom si necesita
        // contextual(Uuid::class, UuidSerializer)
    }

    inline fun <reified T> decodeFromRow(row: Row): T =
        serializer<T>().deserialize(
            CardioDecoder(row, serializersModule)
        )

    fun <T> decodeFromRow(row: Row, deserializer: DeserializationStrategy<T>): T =
        deserializer.deserialize(CardioDecoder(row, serializersModule))
}