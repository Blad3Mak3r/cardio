package io.github.blad3mak3r.cardio.protocol

/**
 * ErrorResponse / NoticeResponse fields.
 * https://www.postgresql.org/docs/current/protocol-error-fields.html
 */
enum class ErrorField(val byte: Byte) {
    SEVERITY('S'.code.toByte()),
    SEVERITY_NON_LOCALIZED('V'.code.toByte()),
    CODE('C'.code.toByte()),
    MESSAGE('M'.code.toByte()),
    DETAIL('D'.code.toByte()),
    HINT('H'.code.toByte()),
    POSITION('P'.code.toByte()),
    INTERNAL_POSITION('p'.code.toByte()),
    INTERNAL_QUERY('q'.code.toByte()),
    WHERE('W'.code.toByte()),
    SCHEMA_NAME('s'.code.toByte()),
    TABLE_NAME('t'.code.toByte()),
    COLUMN_NAME('c'.code.toByte()),
    DATA_TYPE_NAME('d'.code.toByte()),
    CONSTRAINT_NAME('n'.code.toByte()),
    FILE('F'.code.toByte()),
    LINE('L'.code.toByte()),
    ROUTINE('R'.code.toByte());

    companion object {
        fun fromByte(b: Byte) = entries.firstOrNull { it.byte == b }
    }
}