package io.github.blad3mak3r.cardio.protocol

/**
 *  * Description of a column in [PgMessage.RowDescription].
 */
data class FieldDescription(
    val name: String,
    val tableOid: Int,
    val columnAttribute: Short,
    val typeOid: Int,
    val typeSize: Short,
    val typeMod: Int,
    val format: ResultFormat
)
