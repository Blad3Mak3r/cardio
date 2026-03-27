package io.github.blad3mak3r.cardio.protocol

import io.github.blad3mak3r.cardio.protocol.codec.Param
import io.github.blad3mak3r.cardio.protocol.codec.toParam

/**
 * A thin [ArrayList] subtype that represents a PostgreSQL array value.
 *
 * Use [PgArray.of] for quick construction, or simply pass any [List] as a
 * query parameter — `toParam()` handles both transparently.
 */
class PgArray<T> : ArrayList<T>() {
    companion object {
        fun <T> of(vararg elements: T): PgArray<T> = PgArray<T>().apply { addAll(elements) }
    }
}

/** Encode a [PgArray] as a query parameter (delegates to [toParam] via the [List] branch). */
fun <T : Any> PgArray<T>.toParam(): Param<*> = (this as List<*>).toParam()

