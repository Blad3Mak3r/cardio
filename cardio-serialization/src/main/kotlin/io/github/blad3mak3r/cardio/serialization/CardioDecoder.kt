package io.github.blad3mak3r.cardio.serialization

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.BoolCodec
import io.github.blad3mak3r.cardio.protocol.codec.ByteArrayCodec
import io.github.blad3mak3r.cardio.protocol.codec.Float4Codec
import io.github.blad3mak3r.cardio.protocol.codec.Float8Codec
import io.github.blad3mak3r.cardio.protocol.codec.InstantCodec
import io.github.blad3mak3r.cardio.protocol.codec.Int2Codec
import io.github.blad3mak3r.cardio.protocol.codec.Int4Codec
import io.github.blad3mak3r.cardio.protocol.codec.Int8Codec
import io.github.blad3mak3r.cardio.protocol.codec.KotlinUuidCodec
import io.github.blad3mak3r.cardio.protocol.codec.LocalDateCodec
import io.github.blad3mak3r.cardio.protocol.codec.TextCodec
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.ExperimentalUuidApi

class CardioDecoder(
    private val row: Row,
    override val serializersModule: SerializersModule = EmptySerializersModule()
) : Decoder, CompositeDecoder {

    private var currentIndex = 0

    private var currentColumnName = ""

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this

    override fun decodeBoolean(): Boolean = row.get(currentColumnName, BoolCodec)
    override fun decodeByte(): Byte = row.get(currentColumnName, Int8Codec).toByte()
    override fun decodeChar(): Char = row.get(currentColumnName, TextCodec).first()
    override fun decodeShort(): Short = row.get(currentColumnName, Int2Codec)
    override fun decodeInt(): Int = row.get(currentColumnName, Int4Codec)
    override fun decodeLong(): Long = row.get(currentColumnName, Int8Codec)
    override fun decodeFloat(): Float = row.get(currentColumnName, Float4Codec)
    override fun decodeDouble(): Double = row.get(currentColumnName, Float8Codec)
    override fun decodeString(): String = row.get(currentColumnName, TextCodec)
    @ExperimentalSerializationApi
    override fun decodeNull(): Nothing? = null
    @ExperimentalSerializationApi
    override fun decodeNotNullMark(): Boolean = row.getOrNull(currentColumnName, TextCodec) != null
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val value = row.get(currentColumnName, TextCodec).uppercase()
        return (0 until enumDescriptor.elementsCount).firstOrNull { i ->
            enumDescriptor.getElementName(i).uppercase() == value
        } ?: error(
            "Cardio: valor '$value' no encontrado en enum '${enumDescriptor.serialName}'. " +
                    "Valores válidos: ${(0 until enumDescriptor.elementsCount).map { enumDescriptor.getElementName(it) }}")
    }

    @OptIn(ExperimentalUuidApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {

        return when (deserializer.descriptor.serialName) {
            "kotlin.uuid.Uuid"    -> row.get(currentColumnName, KotlinUuidCodec) as T
            "java.time.Instant",
            "kotlinx.datetime.Instant" -> row.get(currentColumnName, InstantCodec) as T
            "java.time.LocalDate",
            "kotlinx.datetime.LocalDate" -> row.get(currentColumnName, LocalDateCodec) as T
            "kotlin.ByteArray"    -> row.get(currentColumnName, ByteArrayCodec) as T
            "kotlin.String"       -> row.get(currentColumnName, TextCodec) as T

            else -> deserializer.deserialize(
                CardioDecoder(row, serializersModule)
            )
        }
    }

    @ExperimentalSerializationApi
    @OptIn(ExperimentalUuidApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeNullableSerializableValue(
        deserializer: DeserializationStrategy<T?>,
    ): T? {
        return when (deserializer.descriptor.serialName) {
            "kotlin.uuid.Uuid"    -> row.get(currentColumnName, KotlinUuidCodec) as T
            "java.time.Instant",
            "kotlinx.datetime.Instant" -> row.getOrNull(currentColumnName, InstantCodec) as T?
            "java.time.LocalDate",
            "kotlinx.datetime.LocalDate" -> row.getOrNull(currentColumnName, LocalDateCodec) as T?
            "kotlin.ByteArray"    -> row.getOrNull(currentColumnName, ByteArrayCodec) as T?
            "kotlin.String"       -> row.getOrNull(currentColumnName, TextCodec) as T?
            else -> {

                if (row.columnNames.contains(currentColumnName) &&
                    row.getOrNull<String>(currentColumnName) == null) return null
                deserializer.deserialize(CardioDecoder(row, serializersModule))
            }
        }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (currentIndex >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE

        val elementName = descriptor.getElementName(currentIndex)

        // @SerialName tiene prioridad, si no: camelCase → snake_case
        currentColumnName = elementName.camelToSnakeCase()

        return currentIndex++
    }

    override fun endStructure(descriptor: SerialDescriptor) = Unit

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        currentColumnName = columnName(descriptor, index)
        return decodeBoolean()
    }
    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        currentColumnName = columnName(descriptor, index)
        return decodeByte()
    }
    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        currentColumnName = columnName(descriptor, index)
        return decodeChar()
    }
    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        currentColumnName = columnName(descriptor, index)
        return decodeShort()
    }
    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        currentColumnName = columnName(descriptor, index)
        return decodeInt()
    }
    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        currentColumnName = columnName(descriptor, index)
        return decodeLong()
    }
    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        currentColumnName = columnName(descriptor, index)
        return decodeFloat()
    }
    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        currentColumnName = columnName(descriptor, index)
        return decodeDouble()
    }
    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        currentColumnName = columnName(descriptor, index)
        return decodeString()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        currentColumnName = columnName(descriptor, index)
        return decodeSerializableValue(deserializer)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        currentColumnName = columnName(descriptor, index)
        return decodeNullableSerializableValue(deserializer)
    }

    private fun columnName(descriptor: SerialDescriptor, index: Int): String =
        descriptor.getElementName(index).camelToSnakeCase()

    private fun String.camelToSnakeCase(): String = buildString {
        this@camelToSnakeCase.forEachIndexed { i, c ->
            if (c.isUpperCase() && i > 0) append('_')
            append(c.lowercaseChar())
        }
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        currentColumnName = columnName(descriptor, index)
        return this
    }

}