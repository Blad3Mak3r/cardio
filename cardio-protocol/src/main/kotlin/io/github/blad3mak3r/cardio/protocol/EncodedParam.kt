package io.github.blad3mak3r.cardio.protocol

/**
 * A query parameter that has already been encoded into its PostgreSQL binary or text
 * wire representation, ready to be included in a [PgMessage.Bind] message.
 *
 * @property bytes The encoded parameter bytes, or `null` to represent a SQL `NULL` value.
 * @property format The wire-protocol encoding format of [bytes].
 *                  Defaults to [ResultFormat.BINARY].
 */
data class EncodedParam(
    val bytes: ByteArray?,
    val format: ResultFormat = ResultFormat.BINARY
)
