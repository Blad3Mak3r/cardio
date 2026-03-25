package io.github.blad3mak3r.cardio.protocol

import io.ktor.utils.io.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

object PgMessageReader {

    suspend fun read(channel: ByteReadChannel): PgMessage.Backend {
        val typeByte = channel.readByte()
        val length = channel.readInt() - 4

        val bytes = ByteArray(length)
        channel.readFully(bytes)

        val payload = Buffer().also {
            it.write(bytes)
        }

        return decode(typeByte, payload)
    }

    private fun decode(type: Byte, src: Buffer): PgMessage.Backend =
        when (type.toInt().toChar()) {
            'R'  -> decodeAuthentication(src)
            'S'  -> PgMessage.ParameterStatus(src.readCString(), src.readCString())
            'K'  -> PgMessage.BackendKeyData(src.readInt(), src.readInt())
            'Z'  -> PgMessage.ReadyForQuery(TransactionStatus.fromByte(src.readByte()))
            'T'  -> decodeRowDescription(src)
            'D'  -> decodeDataRow(src)
            'C'  -> PgMessage.CommandComplete(src.readCString())
            '1'  -> PgMessage.ParseComplete
            '2'  -> PgMessage.BindComplete
            '3'  -> PgMessage.CloseComplete
            't'  -> PgMessage.ParameterDescription(List(src.readShort().toInt()) { src.readInt() })
            'n'  -> PgMessage.NoData
            'E'  -> PgMessage.ErrorResponse(decodeErrorFields(src))
            'N'  -> PgMessage.NoticeResponse(decodeErrorFields(src))
            'A'  -> PgMessage.NotificationResponse(src.readInt(), src.readCString(), src.readCString())
            'I'  -> PgMessage.EmptyQueryResponse
            else -> error(
                "Unknown backend message: '${type.toInt().toChar()}' " +
                        "(0x${type.toUByte().toString(16).padStart(2, '0')})"
            )
        }

    private fun decodeAuthentication(src: Buffer): PgMessage.Authentication =
        when (val authType = src.readInt()) {
            0    -> PgMessage.Authentication.Ok
            5    -> PgMessage.Authentication.MD5(src.readByteArray(4))
            10   -> {
                val mechanisms = buildList {
                    while (!src.exhausted()) {
                        val m = src.readCString()
                        if (m.isEmpty()) break
                        add(m)
                    }
                }
                PgMessage.Authentication.SASL(mechanisms)
            }
            11   -> PgMessage.Authentication.SASLContinue(src.readByteArray(src.size.toInt()))
            12   -> PgMessage.Authentication.SASLFinal(src.readByteArray(src.size.toInt()))
            else -> PgMessage.Authentication.Unknown(authType)
        }

    private fun decodeRowDescription(src: Buffer): PgMessage.RowDescription =
        PgMessage.RowDescription(List(src.readShort().toInt()) {
            FieldDescription(
                name            = src.readCString(),
                tableOid        = src.readInt(),
                columnAttribute = src.readShort(),
                typeOid         = src.readInt(),
                typeSize        = src.readShort(),
                typeMod         = src.readInt(),
                format          = if (src.readShort() == 1.toShort()) ResultFormat.BINARY
                else ResultFormat.TEXT,
            )
        })

    private fun decodeDataRow(src: Buffer): PgMessage.DataRow =
        PgMessage.DataRow(List(src.readShort().toInt()) {
            val len = src.readInt()
            if (len == -1) null else src.readByteArray(len)
        })

    private fun decodeErrorFields(src: Buffer): Map<ErrorField, String> {
        val map = mutableMapOf<ErrorField, String>()
        while (!src.exhausted()) {
            val b = src.readByte()
            if (b == 0.toByte()) break
            val value = src.readCString()
            ErrorField.fromByte(b)?.let { map[it] = value }
        }
        return map
    }

    /**
     * Reads bytes until \0, returns String without the terminator
      */
    private fun Buffer.readCString(): String {
        val sb = StringBuilder()
        while (!exhausted()) {
            val b = readByte()
            if (b == 0.toByte()) break
            sb.append(b.toInt().toChar())
        }
        return sb.toString()
    }


}