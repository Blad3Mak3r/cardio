package io.github.blad3mak3r.cardio.protocol

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Encodes and writes PostgreSQL frontend messages to a byte representation
 * or directly to a [ByteWriteChannel].
 *
 * Each [PgMessage.Frontend] variant is serialized according to the
 * PostgreSQL frontend/backend protocol (version 3.0 / 3.2).
 */
object PgMessageWriter {

    /**
     * Encodes a [PgMessage.Frontend] into a [ByteArray] following the
     * PostgreSQL wire protocol format.
     *
     * @param message The frontend message to encode.
     * @return A [ByteArray] containing the fully serialized message.
     */
    fun encode(message: PgMessage.Frontend): ByteArray =
        Buffer().also { it.writeMessage(message) }.readByteArray()

    /**
     * Encodes and writes a [PgMessage.Frontend] directly to the given [channel],
     * flushing it afterwards.
     *
     * @param channel The [ByteWriteChannel] to write the encoded message to.
     * @param message The frontend message to encode and send.
     */
    suspend fun write(channel: ByteWriteChannel, message: PgMessage.Frontend) {
        channel.writeFully(encode(message))
        channel.flush()
    }

    /**
     * Dispatches serialization of [msg] to the appropriate write function
     * based on its concrete type.
     *
     * @param msg The frontend message to serialize into this [Buffer].
     */
    private fun Buffer.writeMessage(msg: PgMessage.Frontend) = when (msg) {
        is PgMessage.StartupMessage      -> writeStartup(msg)
        is PgMessage.PasswordMessage     -> withType('p') { writeCString(msg.password) }
        is PgMessage.SaslInitialResponse -> withType('p') {
            writeCString(msg.mechanism)
            writeInt(msg.clientFirstMessage.size)
            write(msg.clientFirstMessage)
        }
        is PgMessage.SaslResponse        -> withType('p') { write(msg.clientFinalMessage) }
        is PgMessage.Query               -> withType('Q') { writeCString(msg.sql) }
        is PgMessage.Parse               -> withType('P') {
            writeCString(msg.statementName)
            writeCString(msg.sql)
            writeShort(msg.paramTypeOids.size.toShort())
            msg.paramTypeOids.forEach { writeInt(it) }
        }
        is PgMessage.Bind                -> writeBind(msg)
        is PgMessage.Describe            -> withType('D') {
            writeByte(msg.target.byte)
            writeCString(msg.name)
        }
        is PgMessage.Execute             -> withType('E') {
            writeCString(msg.portalName)
            writeInt(msg.maxRows)
        }
        is PgMessage.CancelRequest       -> {
            writeInt(4 + 4 + 4 + msg.cancelKey.size)
            writeInt(80877102)
            writeInt(msg.processId)
            write(msg.cancelKey)
        }
        PgMessage.Sync                   -> writeEmpty('S')
        PgMessage.Flush                  -> writeEmpty('H')
        PgMessage.Terminate              -> writeEmpty('X')
    }

    /**
     * Serializes a [PgMessage.StartupMessage] into this [Buffer].
     *
     * Unlike other frontend messages, the startup message has no leading type byte.
     * It begins with a 4-byte total length, followed by the protocol version
     * (3.2 = 196610), the connection parameters as null-terminated key/value pairs,
     * and a final null byte to terminate the parameter list.
     *
     * @param msg The startup message containing username, database, and optional parameters.
     */
    private fun Buffer.writeStartup(msg: PgMessage.StartupMessage) {
        val body = Buffer().apply {
            writeInt(196610)  // protocol version 3.2 (major=3, minor=2)
            writeCString("user");     writeCString(msg.username)
            writeCString("database"); writeCString(msg.database)
            msg.params.forEach { (k, v) -> writeCString(k); writeCString(v) }
            writeByte(0)
        }
        writeInt(body.size.toInt() + 4)
        transferFrom(body)
    }

    /**
     * Serializes a [PgMessage.Bind] message into this [Buffer].
     *
     * Writes the portal name, statement name, parameter format codes, parameter
     * values (with `-1` length for null values), and a single result format code
     * applied to all output columns.
     *
     * @param msg The bind message containing portal, statement, parameters, and result format.
     */
    private fun Buffer.writeBind(msg: PgMessage.Bind) = withType('B') {
        writeCString(msg.portalName)
        writeCString(msg.statementName)
        // Parameter format codes
        writeShort(msg.params.size.toShort())
        msg.params.forEach { writeShort(it.format.code) }
        // Values
        writeShort(msg.params.size.toShort())
        msg.params.forEach { param ->
            if (param.bytes == null) writeInt(-1)
            else { writeInt(param.bytes.size); write(param.bytes) }
        }
        // A single result format code for all columns
        writeShort(1.toShort())
        writeShort(msg.resultFormat.code)
    }

    /**
     * Writes a typed message frame into this [Buffer].
     *
     * Executes [block] into a temporary [Buffer] to calculate the body size,
     * then writes the message type byte, the 4-byte length (body size + 4),
     * and finally the body itself.
     *
     * @param type The single-character message type identifier.
     * @param block The builder block that writes the message body.
     */
    private fun Buffer.withType(type: Char, block: Buffer.() -> Unit) {
        val body = Buffer().apply(block)
        writeByte(type.code.toByte())
        writeInt(body.size.toInt() + 4)
        transferFrom(body)
    }

    /**
     * Writes a message with no payload (e.g. Sync, Flush, Terminate).
     *
     * Consists of a single type byte followed by the 4-byte length field
     * whose value is always `4` (length field itself, no body).
     *
     * @param type The single-character message type identifier.
     */
    private fun Buffer.writeEmpty(type: Char) {
        writeByte(type.code.toByte())
        writeInt(4)
    }

    /**
     * Writes a null-terminated UTF-8 string (C-string) into this [Buffer].
     *
     * @param s The string to encode and write.
     */
    private fun Buffer.writeCString(s: String) {
        write(s.toByteArray(Charsets.UTF_8))
        writeByte(0)
    }
}