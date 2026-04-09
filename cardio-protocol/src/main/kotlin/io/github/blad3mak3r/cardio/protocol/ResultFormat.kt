package io.github.blad3mak3r.cardio.protocol

/**
 * Specifies the wire-protocol encoding format for query results and bind parameters.
 *
 * PostgreSQL allows each column to be returned in either text or binary format.
 * Cardio always requests [BINARY] for all columns to maximise decode performance.
 *
 * @property code The 16-bit code sent on the wire (`0` = text, `1` = binary).
 */
enum class ResultFormat(val code: Short) {
    /** Human-readable text representation. */
    TEXT(0),

    /** Compact binary representation. Used by default for all Cardio queries. */
    BINARY(1);
}