package io.github.blad3mak3r.cardio.protocol

/**
 * Metadata for a single column in a [PgMessage.RowDescription] message.
 *
 * This information is returned by the server before the first [PgMessage.DataRow]
 * and is used to decode each column's binary payload into the correct Kotlin type.
 *
 * @property name            Column name as reported by the server.
 * @property tableOid        OID of the table the column belongs to, or `0` if not
 *                           applicable (e.g. the column is an expression).
 * @property columnAttribute Attribute number of the column within its table, or `0`
 *                           if not applicable.
 * @property typeOid         OID of the PostgreSQL data type for this column.
 *                           See [PgOid] for well-known OID constants.
 * @property typeSize        Server-reported size of the data type in bytes.
 *                           Negative values indicate variable-length types.
 * @property typeMod         Type-specific modifier (e.g. precision/scale for `NUMERIC`,
 *                           or the declared length for `VARCHAR(n)`). `-1` if unused.
 * @property format          Wire-protocol format in which the column data is returned.
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
