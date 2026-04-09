package io.github.blad3mak3r.cardio.protocol

/**
 * Identifies the target of a PostgreSQL `Describe` message in the extended query protocol.
 *
 * @property byte The single ASCII byte used in the wire protocol to identify the target.
 */
enum class DescribeTarget(val byte: Byte) {
    /** Describes a prepared statement (created by a preceding `Parse` message). */
    STATEMENT('S'.code.toByte()),

    /** Describes a portal (created by a preceding `Bind` message). */
    PORTAL('P'.code.toByte());
}