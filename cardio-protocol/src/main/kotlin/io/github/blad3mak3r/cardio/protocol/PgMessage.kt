package io.github.blad3mak3r.cardio.protocol

/**
 * Sealed hierarchy of all PostgreSQL wire-protocol messages (version 3.0).
 *
 * The hierarchy is split into two sub-hierarchies:
 * - [Frontend] — messages sent **from the client to the server**.
 * - [Backend]  — messages sent **from the server to the client**.
 *
 * Encoding of [Frontend] messages is handled by [PgMessageWriter]; decoding of
 * [Backend] messages is handled by [PgMessageReader].
 */
sealed interface PgMessage {

    /**
     * Marker interface for messages that originate from the client (frontend).
     */
    sealed interface Frontend : PgMessage

    /**
     * Initiates a new database session. Sent as the very first message after the
     * TCP (and optional TLS) connection is established.
     *
     * Unlike all other frontend messages, this message has **no leading type byte**.
     * It starts with a 4-byte total length, followed by the protocol version
     * (`196608` = 3.0), and then the startup parameters as null-terminated key/value pairs.
     *
     * @property username The name of the database user to authenticate as.
     * @property database The name of the database to connect to.
     * @property params   Optional extra startup parameters sent to the server
     *                    (e.g. `client_encoding`, `application_name`).
     */
    data class StartupMessage(
        val username: String,
        val database: String,
        val params: Map<String, String> = mapOf(
            "client_encoding" to "UTF8",
            "application_name" to "cardio-pg-client",
        )
    ) : Frontend

    /**
     * Carries a cleartext or hashed password in response to an [Authentication.MD5]
     * or `AuthenticationCleartextPassword` challenge.
     *
     * Wire format: `'p'` | Int32 length | String `"md5<hash>\0"`
     *
     * @property password The encoded password string to send (e.g. `"md5<hexhash>"`).
     */
    data class PasswordMessage(
        val password: String
    ) : Frontend

    /**
     * First message in the SASL authentication exchange. Carries the chosen SASL
     * mechanism name and the initial client message produced by that mechanism.
     *
     * @property mechanism          The SASL mechanism name (e.g. `"SCRAM-SHA-256"`).
     * @property clientFirstMessage The mechanism-specific initial client message bytes.
     */
    data class SaslInitialResponse(
        val mechanism: String,
        val clientFirstMessage: ByteArray
    ) : Frontend

    /**
     * Subsequent SASL client message sent in response to a [Authentication.SASLContinue]
     * challenge from the server.
     *
     * @property clientFinalMessage The mechanism-specific client-final message bytes.
     */
    data class SaslResponse(
        val clientFinalMessage: ByteArray
    ) : Frontend

    /**
     * Simple (non-extended) query message. Sends a raw SQL string to the server
     * for immediate execution. Not used by Cardio's extended-query flow.
     *
     * @property sql The SQL string to execute.
     */
    data class Query(
        val sql: String
    ) : Frontend

    /**
     * Extended-query `Parse` message. Instructs the server to parse [sql] into a
     * named (or unnamed) prepared statement.
     *
     * @property statementName  Name of the prepared statement. Empty string for the
     *                          unnamed statement, which is implicitly closed on re-use.
     * @property sql            The SQL string to parse.
     * @property paramTypeOids  OIDs of the expected parameter types, used by the server
     *                          to resolve type ambiguities. An empty list lets the server
     *                          infer all types.
     */
    data class Parse(
        val statementName: String = "",
        val sql: String,
        val paramTypeOids: List<Int> = emptyList()
    ) : Frontend

    /**
     * Extended-query `Bind` message. Binds a prepared statement to a portal by
     * supplying concrete parameter values and specifying the desired result format.
     *
     * @property portalName    Name of the destination portal. Empty string for the
     *                         unnamed portal.
     * @property statementName Name of the prepared statement to bind. Empty string
     *                         for the unnamed statement.
     * @property params        Encoded parameter values to bind (see [EncodedParam]).
     * @property resultFormat  Encoding format requested for all result columns.
     */
    data class Bind(
        val portalName: String = "",
        val statementName: String = "",
        val params: List<EncodedParam>,
        val resultFormat: ResultFormat = ResultFormat.BINARY
    ) : Frontend

    /**
     * Extended-query `Describe` message. Requests metadata about a prepared statement
     * or portal from the server (e.g. the [RowDescription] for a portal).
     *
     * @property target Whether to describe a [DescribeTarget.STATEMENT] or [DescribeTarget.PORTAL].
     * @property name   Name of the target. Empty string refers to the unnamed target.
     */
    data class Describe(
        val target: DescribeTarget,
        val name: String = ""
    ) : Frontend

    /**
     * Extended-query `Execute` message. Runs a previously bound portal and fetches
     * up to [maxRows] rows (0 means unlimited).
     *
     * @property portalName Name of the portal to execute. Empty string for the unnamed portal.
     * @property maxRows    Maximum number of rows to return. `0` requests all rows.
     */
    data class Execute(
        val portalName: String = "",
        val maxRows: Int = 0
    ) : Frontend

    /** Synchronises the extended-query protocol, causing the server to send a [ReadyForQuery]. */
    data object Sync : Frontend

    /** Requests the server to flush its output buffer. */
    data object Flush : Frontend

    /**
     * Extended-query `Close` message. Closes a prepared statement or portal, freeing
     * any server-side resources associated with it.
     *
     * @property target Whether to close a [DescribeTarget.STATEMENT] or [DescribeTarget.PORTAL].
     * @property name   Name of the target. Empty string refers to the unnamed target.
     */
    data class Close(
        val target: DescribeTarget,
        val name: String = ""
    ) : Frontend

    /** Instructs the server to close the session gracefully. */
    data object Terminate : Frontend

    /**
     * Out-of-band cancel request sent on a **separate** TCP connection to ask the server
     * to cancel the query running on the backend identified by [processId] and [secretKey].
     *
     * @property processId  Process ID of the backend to cancel (received as [BackendKeyData.processId]).
     * @property secretKey  Secret key of the backend (received as [BackendKeyData.secretKey]).
     */
    data class CancelRequest(
        val processId: Int,
        val secretKey: Int
    ) : Frontend

    /**
     * Marker interface for messages that originate from the server (backend).
     */
    sealed interface Backend : PgMessage

    /**
     * Authentication challenge or success notification sent by the server.
     */
    sealed interface Authentication : Backend {

        /** Authentication succeeded; no further credentials are required. */
        data object Ok : Authentication

        /**
         * MD5 password challenge. The client must reply with a [PasswordMessage]
         * carrying `"md5" + MD5(MD5(password + username) + salt)`.
         *
         * @property salt 4-byte random salt chosen by the server.
         */
        data class MD5(
            val salt: ByteArray
        ) : Authentication

        /**
         * SASL authentication challenge. The client must choose one of the offered
         * [mechanisms] and reply with a [SaslInitialResponse].
         *
         * @property mechanisms List of SASL mechanism names the server supports
         *                      (e.g. `["SCRAM-SHA-256"]`).
         */
        data class SASL(
            val mechanisms: List<String>
        ) : Authentication

        /**
         * Intermediate SASL server message (server-first-message in SCRAM).
         *
         * @property data Raw bytes of the server's SASL challenge.
         */
        data class SASLContinue(
            val data: ByteArray
        ) : Authentication

        /**
         * Final SASL server message (server-final-message in SCRAM) carrying the
         * server's proof that it knows the correct password.
         *
         * @property data Raw bytes of the server's final SASL message.
         */
        data class SASLFinal(
            val data: ByteArray
        ) : Authentication

        /**
         * An authentication type that Cardio does not support.
         *
         * @property authType The numeric authentication type identifier received from the server.
         */
        data class Unknown(
            val authType: Int
        ) : Authentication
    }

    /**
     * Reports the current value of a run-time server parameter (e.g. `server_version`,
     * `TimeZone`, `integer_datetimes`). Sent during startup and whenever a parameter
     * changes during the session.
     *
     * @property name  Parameter name.
     * @property value Current parameter value.
     */
    data class ParameterStatus(
        val name: String,
        val value: String
    ) : Backend

    /**
     * Carries the process ID and secret key of the backend process. Clients store
     * these values to be able to send a [CancelRequest] if needed.
     *
     * @property processId Backend process ID.
     * @property secretKey Backend secret key.
     */
    data class BackendKeyData(
        val processId: Int,
        val secretKey: Int,
    ) : Backend

    /**
     * Sent by the server when it is ready to accept the next query or command.
     * The [status] field reflects the current transaction state.
     *
     * @property status Current [TransactionStatus] of the backend session.
     */
    data class ReadyForQuery(
        val status: TransactionStatus
    ) : Backend

    /**
     * Describes the columns of the result set that will follow in [DataRow] messages.
     *
     * @property fields Ordered list of [FieldDescription] entries, one per result column.
     */
    data class RowDescription(
        val fields: List<FieldDescription>
    ) : Backend {
        /**
         * Maps lowercased column names to their zero-based index, for case-insensitive
         * column lookup. Computed once per result set and shared by every [DataRow] /
         * [io.github.blad3mak3r.cardio.protocol.Row] built from this description.
         */
        val indexByName: Map<String, Int> by lazy {
            fields.mapIndexed { i, f -> f.name.lowercase() to i }.toMap()
        }
    }

    /**
     * Carries the binary or text data for one row of a query result.
     *
     * @property columns Ordered list of raw column bytes, one entry per result column.
     *                   A `null` entry represents a SQL `NULL` value.
     */
    data class DataRow(
        val columns: List<ByteArray?>
    ) : Backend

    /**
     * Confirms the successful completion of a SQL command and reports the number of
     * rows affected.
     *
     * @property tag            The command-tag string returned by the server
     *                          (e.g. `"INSERT 0 1"`, `"UPDATE 3"`, `"SELECT 5"`).
     * @property rowsAffected   Number of rows inserted, updated, deleted, or selected,
     *                          parsed from the trailing number in [tag].
     */
    data class CommandComplete(
        val tag: String
    ) : Backend {
        val rowsAffected: Long
            get() = tag.split(" ").lastOrNull()?.toLongOrNull() ?: 0L
    }

    /** Indicates that a `Parse` message was processed successfully. */
    data object ParseComplete : Backend

    /** Indicates that a `Bind` message was processed successfully. */
    data object BindComplete : Backend

    /** Indicates that a `Close` message was processed successfully. */
    data object CloseComplete : Backend

    /**
     * Sent by the server when an `Execute` message reached [Execute.maxRows] before the
     * portal was exhausted.  The portal remains open; the client may send another
     * [Execute] to fetch more rows.
     */
    data object PortalSuspended : Backend

    /** Sent in response to a `Describe` message when the portal returns no rows. */
    data object NoData : Backend

    /**
     * Describes the OIDs of the parameters expected by a prepared statement, sent in
     * response to a `Describe(STATEMENT)` message.
     *
     * @property paramTypeOids Ordered list of parameter type OIDs.
     */
    data class ParameterDescription(
        val paramTypeOids: List<Int>
    ) : Backend

    /**
     * Sent by the server when an error occurs. Contains structured diagnostic fields
     * (see [ErrorField]). Cardio converts this message into a [PgException] via
     * [ErrorResponse.toException].
     *
     * @property fields Map of [ErrorField] identifiers to their string values.
     */
    data class ErrorResponse(
        val fields: Map<ErrorField, String>
    ) : Backend {

        /** Severity label (e.g. `ERROR`, `FATAL`). */
        val severity: String
            get() = fields[ErrorField.SEVERITY] ?: "ERROR"

        /** Five-character SQLSTATE code (e.g. `42P01`). */
        val sqlState: String
            get() = fields[ErrorField.CODE] ?: "00000"

        /** Primary human-readable error message. */
        val message: String
            get() = fields[ErrorField.MESSAGE] ?: "Unknown error"

        /** Optional secondary detail message. */
        val detail: String?
            get() = fields[ErrorField.DETAIL]

        /** Optional hint on how to resolve the error. */
        val hint: String?
            get() = fields[ErrorField.HINT]

        /**
         * Converts this error response into a [PgException] that can be thrown
         * and caught by the application.
         */
        fun toException(sql: String? = null): PgException = PgException(
            severity = severity,
            sqlState = sqlState,
            message = message,
            detail = detail,
            hint = hint,
            sql = sql,
        )
    }

    /**
     * Non-fatal server notice carrying the same structured fields as [ErrorResponse].
     * Cardio currently ignores notice messages.
     *
     * @property fields Map of [ErrorField] identifiers to their string values.
     */
    data class NoticeResponse(
        val fields: Map<ErrorField, String>
    ) : Backend

    /**
     * Asynchronous notification delivered when the backend receives a `NOTIFY` command
     * on a channel that this session is listening to via `LISTEN`.
     *
     * @property processId Backend process ID of the notifying session.
     * @property channel   Name of the notification channel.
     * @property payload   Optional application-defined payload string.
     */
    data class NotificationResponse(
        val processId: Int,
        val channel: String,
        val payload: String
    ) : Backend

    /** Sent in response to an empty query string. */
    data object EmptyQueryResponse : Backend

    /**
     * Sent when the server is ready to receive `COPY` data from the client.
     *
     * @property format        Overall copy format (`0` = text, `1` = binary).
     * @property columnFormats Per-column format codes.
     */
    data class CopyInResponse(
        val format: Int,
        val columnFormats: List<Int>
    ) : Backend

    /**
     * Sent when the server is about to stream `COPY` data to the client.
     *
     * @property format        Overall copy format (`0` = text, `1` = binary).
     * @property columnFormats Per-column format codes.
     */
    data class CopyOutResponse(
        val format: Int,
        val columnFormats: List<Int>
    ) : Backend



}