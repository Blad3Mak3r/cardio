package io.github.blad3mak3r.cardio.protocol

/**
 * Reflects the transaction state reported by the PostgreSQL backend in a
 * [PgMessage.ReadyForQuery] message.
 *
 * @property byte The single ASCII byte used in the wire protocol to identify the status.
 */
enum class TransactionStatus(val byte: Byte) {
    /** No transaction is currently active. */
    IDLE('I'.code.toByte()),

    /** A transaction block is open and in progress. */
    IN_TRANSACTION('T'.code.toByte()),

    /** A transaction block is open but has encountered an error; only `ROLLBACK` is allowed. */
    FAILED('E'.code.toByte());

    companion object {
        /**
         * Returns the [TransactionStatus] corresponding to [b], defaulting to [IDLE]
         * for unrecognised byte values.
         *
         * @param b The status byte received from the server.
         */
        fun fromByte(b: Byte): TransactionStatus = entries.firstOrNull { it.byte == b } ?: IDLE
    }
}