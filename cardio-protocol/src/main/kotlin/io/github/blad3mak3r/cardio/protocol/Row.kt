package io.github.blad3mak3r.cardio.protocol

import io.github.blad3mak3r.cardio.protocol.codec.TypeCodec
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry

class Row @PublishedApi internal constructor(
    @PublishedApi internal val description: PgMessage.RowDescription,
    @PublishedApi internal val data: PgMessage.DataRow,
    @PublishedApi internal val registry: TypeCodecRegistry
) {
    @PublishedApi
    internal val indexByName: Map<String, Int> = description.fields.mapIndexed { i, f ->
        f.name.lowercase() to i
    }.toMap()

    fun <T : Any> get(name: String, codec: TypeCodec<T>): T =
        getOrNull(name, codec) ?: error("Column '$name' is null — use getOrNull()")
    fun <T : Any> getOrNull(name: String, codec: TypeCodec<T>): T? =
        codec.decode(columnBytes(name))

    fun <T : Any> get(index: Int, codec: TypeCodec<T>): T =
        getOrNull(index, codec) ?: error("Column at index $index is null — use getOrNull()")

    fun <T : Any> getOrNull(index: Int, codec: TypeCodec<T>): T? =
        codec.decode(data.columns[index])

    inline fun <reified T : Any> get(name: String): T =
        getOrNull<T>(name) ?: error("Column '$name' is null — use getOrNull<${T::class.simpleName}>()")

    inline fun <reified T : Any> get(index: Int): T =
        getOrNull<T>(index) ?: error("Column at index $index is null — use getOrNull<${T::class.simpleName}>()")

    inline fun <reified T : Any> getOrNull(index: Int): T? {
        return registry.decodeByOid(description.fields[index].typeOid, data.columns[index])
    }

    inline fun <reified T : Any> getOrNull(name: String): T? {
        val i = index(name)
        return getOrNull(i)
    }

    @PublishedApi
    internal fun index(name: String): Int =
        indexByName[name.lowercase()]
            ?: error("Column '$name' not found. Available: ${indexByName.keys.joinToString()}")

    @PublishedApi
    internal fun columnBytes(name: String): ByteArray? = data.columns[index(name)]

    val columnNames: List<String>
        get() = description.fields.map { it.name }

    val columnCount: Int
        get() = description.fields.size
}
