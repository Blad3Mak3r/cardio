package io.github.blad3mak3r.cardio.protocol

sealed interface PgMessage {

    sealed interface Frontend : PgMessage

    data class StartupMessage(
        val username: String,
        val database: String,
        val params: Map<String, String> = mapOf(
            "client_encoding" to "UTF8",
            "application_name" to "cardio-pg-client",
        )
    ) : Frontend

    /**
     * MD5 auth
     * Byte: 'p' | Int32 length | String "md5<hash>\0"
     */
    data class PasswordMessage(
        val password: String
    ) : Frontend

    data class SaslInitialResponse(
        val mechanism: String,
        val clientFirstMessage: ByteArray
    ) : Frontend

    data class SaslResponse(
        val clientFinalMessage: ByteArray
    ) : Frontend

    data class Query(
        val sql: String
    ) : Frontend

    data class Parse(
        val statementName: String = "",
        val sql: String,
        val paramTypeOid: List<Int> = emptyList()
    ) : Frontend

    data class Bind(
        val portalName: String = "",
        val statementName: String = "",
        val params: List<EncodedParam>,
        val resultFormat: ResultFormat = ResultFormat.BINARY
    ) : Frontend

    data class Describe(
        val target: DescribeTarget,
        val name: String = ""
    ) : Frontend

    data class Execute(
        val portalName: String = "",
        val maxRows: Int = 0
    ) : Frontend

    object Sync : Frontend

    object Flush : Frontend

    object Terminate : Frontend

    data class CancelRequest(
        val processId: Int,
        val secretKey: Int
    ) : Frontend

    sealed interface Backend : PgMessage

    sealed interface Authentication : Backend {

        object Ok : Authentication

        data class MD5(
            val salt: ByteArray
        ) : Authentication

        data class SASL(
            val mechanisms: List<String>
        ) : Authentication

        data class SASLContinue(
            val data: ByteArray
        ) : Authentication

        data class SASLFinal(
            val data: ByteArray
        ) : Authentication

        data class Unknown(
            val authType: Int
        ) : Authentication
    }

    data class ParameterStatus(
        val name: String,
        val value: String
    ) : Backend

    data class BackendKeyData(
        val processId: Int,
        val secretKey: Int,
    ) : Backend

    data class ReadyForQuery(
        val status: TransactionStatus
    ) : Backend

    data class RowDescription(
        val fields: List<FieldDescription>
    ) : Backend

    data class DataRow(
        val colums: List<ByteArray?>
    ) : Backend

    data class CommandComplete(
        val tag: String
    ) : Backend {
        val rowsAffected: Long
            get() = tag.split(" ").lastOrNull()?.toLongOrNull() ?: 0L
    }

    /**
     * EXTENDED QUERY
     */

    object ParseComplete : Backend

    object BindComplete : Backend

    object CloseComplete : Backend

    object NoData : Backend

    data class ParameterDescription(
        val paramTypeOids: List<Int>
    ) : Backend

    data class ErrorResponse(
        val fields: Map<ErrorField, String>
    ) : Backend {

        val severity: String
            get() = fields[ErrorField.SEVERITY] ?: "ERROR"

        val sqlState: String
            get() = fields[ErrorField.CODE] ?: "00000"

        val message: String
            get() = fields[ErrorField.MESSAGE] ?: "Unknown error"

        val detail: String?
            get() = fields[ErrorField.DETAIL]

        val hint: String?
            get() = fields[ErrorField.HINT]

        fun toException(): PgException {
            return PgException(
                severity = severity,
                sqlState = sqlState,
                message = message,
                detail = detail,
                hint = hint
            )
        }
    }

    data class NoticeResponse(
        val fields: Map<ErrorField, String>
    ) : Backend

    data class NotificationResponse(
        val processId: Int,
        val channel: String,
        val payload: String
    ) : Backend

    object EmptyQueryResponse : Backend

    data class CopyInResponse(
        val format: Int,
        val columnFormats: List<Int>
    ) : Backend

    data class CopyOutResponse(
        val format: Int,
        val columnFormats: List<Int>
    ) : Backend



}