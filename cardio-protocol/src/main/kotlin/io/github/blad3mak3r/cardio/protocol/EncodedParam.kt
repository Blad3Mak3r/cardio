package io.github.blad3mak3r.cardio.protocol

/**
 * Already encoded parameter ready to be included in a [PgMessage.Bind].
 */
data class EncodedParam(
    val bytes: ByteArray?,
    val format: ResultFormat = ResultFormat.BINARY
)
