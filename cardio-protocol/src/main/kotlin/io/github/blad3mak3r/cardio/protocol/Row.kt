package io.github.blad3mak3r.cardio.protocol

import io.github.blad3mak3r.cardio.protocol.codec.TypeCodec
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry

/**
 * Represents a single row in a PostgreSQL query result.
 *
 * Column values can be accessed by **name** (case-insensitive) or by zero-based **index**,
 * using either an explicit [TypeCodec] or a reified type parameter that is resolved
 * automatically through the [TypeCodecRegistry].
 *
 * Non-null accessors ([get]) throw if the column value is SQL `NULL`; nullable accessors
 * ([getOrNull]) return `null` in that case.
 *
 * ```kotlin
 * val id:   Int    = row.get("id")
 * val name: String = row.get(1)
 * val tag:  String? = row.getOrNull("tag")
 * ```
 */
class Row @PublishedApi internal constructor(
    @PublishedApi internal val description: PgMessage.RowDescription,
    @PublishedApi internal val data: PgMessage.DataRow,
    @PublishedApi internal val registry: TypeCodecRegistry
) {
    @PublishedApi
    internal val indexByName: Map<String, Int> = description.fields.mapIndexed { i, f ->
        f.name.lowercase() to i
    }.toMap()

    /**
     * Returns the value of the column named [name], decoded with [codec].
     *
     * @throws IllegalStateException if the column value is SQL `NULL`.
     */
    fun <T : Any> get(name: String, codec: TypeCodec<T>): T =
        getOrNull(name, codec) ?: error("Column '$name' is null — use getOrNull()")

    /**
     * Returns the value of the column named [name], decoded with [codec],
     * or `null` if the column value is SQL `NULL`.
     */
    fun <T : Any> getOrNull(name: String, codec: TypeCodec<T>): T? =
        codec.decode(columnBytes(name))

    /**
     * Returns the value of the column at zero-based [index], decoded with [codec].
     *
     * @throws IllegalStateException if the column value is SQL `NULL`.
     */
    fun <T : Any> get(index: Int, codec: TypeCodec<T>): T =
        getOrNull(index, codec) ?: error("Column at index $index is null — use getOrNull()")

    /**
     * Returns the value of the column at zero-based [index], decoded with [codec],
     * or `null` if the column value is SQL `NULL`.
     */
    fun <T : Any> getOrNull(index: Int, codec: TypeCodec<T>): T? =
        codec.decode(data.columns[index])

    /**
     * Returns the value of the column named [name], decoded by looking up the codec
     * for type [T] in the [TypeCodecRegistry].
     *
     * @throws IllegalStateException if the column value is SQL `NULL`.
     * @throws IllegalArgumentException if [name] does not match any column.
     */
    inline fun <reified T : Any> get(name: String): T =
        getOrNull<T>(name) ?: error("Column '$name' is null — use getOrNull<${T::class.simpleName}>()")

    /**
     * Returns the value of the column at zero-based [index], decoded by looking up the
     * codec for type [T] in the [TypeCodecRegistry].
     *
     * @throws IllegalStateException if the column value is SQL `NULL`.
     */
    inline fun <reified T : Any> get(index: Int): T =
        getOrNull<T>(index) ?: error("Column at index $index is null — use getOrNull<${T::class.simpleName}>()")

    /**
     * Returns the value of the column at zero-based [index], decoded by looking up the
     * codec for type [T] in the [TypeCodecRegistry], or `null` if the value is SQL `NULL`.
     */
    inline fun <reified T : Any> getOrNull(index: Int): T? {
        return registry.decodeByOid(description.fields[index].typeOid, data.columns[index])
    }

    /**
     * Returns the value of the column named [name], decoded by looking up the codec
     * for type [T] in the [TypeCodecRegistry], or `null` if the value is SQL `NULL`.
     *
     * Column name lookup is **case-insensitive**.
     *
     * @throws IllegalArgumentException if [name] does not match any column.
     */
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

    /** Ordered list of column names as reported by the server. */
    val columnNames: List<String>
        get() = description.fields.map { it.name }

    /** Number of columns in this row. */
    val columnCount: Int
        get() = description.fields.size
}
