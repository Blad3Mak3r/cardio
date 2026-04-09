package io.github.blad3mak3r.cardio.protocol

import io.github.blad3mak3r.cardio.protocol.codec.Param
import io.github.blad3mak3r.cardio.protocol.codec.toParam

/**
 * A thin [ArrayList] subtype that represents a PostgreSQL array value.
 *
 * `PgArray` can be passed directly as a query parameter; the [toParam] extension
 * converts it via the standard [List] branch of the codec resolution logic.
 *
 * Use [PgArray.of] for quick construction, or simply pass any [List] as a
 * query parameter — [toParam] handles both transparently.
 *
 * @param T Element type of the array.
 */
class PgArray<T> : ArrayList<T>() {
    companion object {
        /**
         * Creates a [PgArray] pre-populated with the given [elements].
         *
         * @param elements The elements to include in the array.
         * @return A new [PgArray] containing all [elements].
         */
        fun <T> of(vararg elements: T): PgArray<T> = PgArray<T>().apply { addAll(elements) }
    }
}

/**
 * Encodes this [PgArray] as a query parameter by delegating to the [List] branch
 * of [toParam].
 */
fun <T : Any> PgArray<T>.toParam(): Param<*> = (this as List<*>).toParam()

