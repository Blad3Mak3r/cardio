package io.github.blad3mak3r.cardio.protocol

enum class TransactionStatus(val byte: Byte) {
    IDLE('I'.code.toByte()),
    IN_TRANSACTION('T'.code.toByte()),
    FAILED('E'.code.toByte());

    companion object {
        fun fromByte(b: Byte): TransactionStatus = entries.firstOrNull { it.byte == b } ?: IDLE
    }
}