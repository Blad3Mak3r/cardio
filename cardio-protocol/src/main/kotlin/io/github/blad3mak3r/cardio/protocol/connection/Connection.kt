package io.github.blad3mak3r.cardio.protocol.connection

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
import io.github.blad3mak3r.cardio.protocol.PgNotification
import io.github.blad3mak3r.cardio.protocol.DescribeTarget
import io.github.blad3mak3r.cardio.protocol.PgException
import io.github.blad3mak3r.cardio.protocol.PgMessage
import io.github.blad3mak3r.cardio.protocol.PgMessageReader
import io.github.blad3mak3r.cardio.protocol.PgMessageWriter
import io.github.blad3mak3r.cardio.protocol.ResultFormat
import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.TransactionStatus
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry
import io.github.blad3mak3r.cardio.protocol.codec.toParam
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.naming.ldap.LdapName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * A single, fully-functional PostgreSQL database connection implementing the
 * PostgreSQL wire protocol (frontend/backend protocol v3).
 *
 * **Lifecycle:** Obtain instances through the [connect] factory function (or via
 * [ConnectionPool], which manages a pool of connections).  The connection is usable
 * immediately after [connect] returns — the startup and authentication handshakes have
 * already completed.
 *
 * **Authentication:** Supports MD5 password and SCRAM-SHA-256 (the PostgreSQL default
 * since PostgreSQL 14).
 *
 * **TLS/SSL:** All five [SslMode] values are supported.  Negotiation follows the
 * PostgreSQL SSLRequest protocol.
 *
 * **Thread safety:** Each operation is protected by a [kotlinx.coroutines.sync.Mutex].
 * A connection must not be shared between concurrent coroutines without external
 * coordination; use [ConnectionPool] for concurrent workloads.
 *
 * @see ConnectionPool
 * @see connect
 */
class Connection private constructor(
    private val config: Configuration,
    private val selectorManager: SelectorManager,
    private val socket: Socket,
    private val readChannel: ByteReadChannel,
    private val writeChannel: ByteWriteChannel,
    internal val registry: TypeCodecRegistry
) : DatabaseOperations {

    /**
     * PostgreSQL TLS/SSL connection modes.
     *
     * - [DISABLE]     – Plain TCP; no SSL attempted.
     * - [PREFER]      – Try SSL; fall back to plain TCP if the server declines.
     * - [REQUIRE]     – Require SSL; accept any server certificate (no verification).
     * - [VERIFY_CA]   – Require SSL; verify the server certificate against a CA but
     *                   skip hostname verification.
     * - [VERIFY_FULL] – Require SSL; verify the server certificate against a CA **and**
     *                   check that the certificate's hostname matches [Configuration.host].
     */
    enum class SslMode { DISABLE, PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL }

    /**
     * Connection configuration.
     *
     * @param host             Hostname or IP address of the PostgreSQL server. Defaults to `"localhost"`.
     * @param port             TCP port of the PostgreSQL server. Defaults to `5432`.
     * @param database         Name of the database to connect to.
     * @param username         PostgreSQL username.
     * @param password         PostgreSQL password.
     * @param sslMode          SSL/TLS mode. Defaults to [SslMode.DISABLE].
     * @param sslRootCert      PEM-encoded CA certificate used for [SslMode.VERIFY_CA] and
     *                         [SslMode.VERIFY_FULL]. When `null` the JVM's default trust store
     *                         is used. Ignored for [SslMode.DISABLE], [SslMode.PREFER], and
     *                         [SslMode.REQUIRE].
     * @param connectTimeoutMs Maximum time in milliseconds for each phase of the connection
     *                         (TCP connect, SSL negotiation, startup/auth). Defaults to `5000`.
     * @param applicationName  Value sent in the `application_name` startup parameter.
     *                         Visible in `pg_stat_activity`. Defaults to `"cardio-pg-client"`.
     */
    data class Configuration(
        val host: String = "localhost",
        val port: Int = 5432,
        val database: String,
        val username: String,
        val password: String,
        val sslMode: SslMode = SslMode.DISABLE,
        val sslRootCert: ByteArray? = null,
        val connectTimeoutMs: Long = 5_000L,
        val applicationName: String = "cardio-pg-client"
    ) {
        // ByteArray doesn't provide structural equals/hashCode in data classes;
        // override them so Configuration equality is content-based.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Configuration) return false
            return host == other.host &&
                port == other.port &&
                database == other.database &&
                username == other.username &&
                password == other.password &&
                sslMode == other.sslMode &&
                sslRootCertEquals(other.sslRootCert) &&
                connectTimeoutMs == other.connectTimeoutMs &&
                applicationName == other.applicationName
        }

        override fun hashCode(): Int {
            var result = host.hashCode()
            result = 31 * result + port
            result = 31 * result + database.hashCode()
            result = 31 * result + username.hashCode()
            result = 31 * result + password.hashCode()
            result = 31 * result + sslMode.hashCode()
            result = 31 * result + (sslRootCert?.contentHashCode() ?: 0)
            result = 31 * result + connectTimeoutMs.hashCode()
            result = 31 * result + applicationName.hashCode()
            return result
        }

        private fun sslRootCertEquals(other: ByteArray?): Boolean =
            if (sslRootCert == null && other == null) true
            else if (sslRootCert == null || other == null) false
            else sslRootCert.contentEquals(other)
    }

    internal sealed interface State {
        data object Connecting     : State
        data object Authenticating : State
        data object Ready          : State
        data object InQuery        : State
        data object InTransaction  : State
        data object Closing        : State
        data class  Failed(val cause: Throwable) : State
    }

    private val mutex = Mutex()

    internal var state: State = State.Connecting
        private set

    /** The process ID of the server backend for this connection, as reported in the `BackendKeyData` startup message. Can be used to cancel in-progress queries. */
    var processId: Int = 0
        private set

    /** The secret cancel key for this connection, as reported in the `BackendKeyData` startup message. Used together with [processId] for query cancellation. */
    var secretKey: Int = 0
        private set

    /** Server runtime parameters received during the startup handshake (e.g. `server_version`, `client_encoding`, `TimeZone`). Updated as `ParameterStatus` messages arrive. */
    val serverParams: MutableMap<String, String> = ConcurrentHashMap()

    /** Returns `true` when the connection is in the [State.Ready] state and may accept queries. */
    val isReady: Boolean
        get() = state == State.Ready

    /** Returns `true` when the connection has entered the [State.Failed] state due to an unrecoverable error and must not be reused. */
    val isFailed: Boolean
        get() = state is State.Failed

    override suspend fun <T> query(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T
    ): List<T> = mutex.withLock {
        check(state == State.Ready || state == State.InTransaction) {
            "Connection is not ready for queries (state = ${state})"
        }

        val prev = state
        state = State.InQuery
        try {
            executeQuery(sql = sql, params = params, mapper = mapper)
        } catch (e: PgException) {
            // drainUntilReady() already called updateTransactionState; restore prev as safety net
            state = prev
            throw e
        } catch (e: Throwable) {
            state = State.Failed(e)
            throw e
        } finally {
            // Fallback: if updateTransactionState wasn't reached, restore the pre-query state
            if (state == State.InQuery) {
                state = prev
            }
        }
    }

    override suspend fun <T> queryOne(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T
    ): T? = mutex.withLock {
        check(state == State.Ready || state == State.InTransaction) {
            "Connection is not ready for queries (state = $state)"
        }

        val prev = state
        state = State.InQuery
        try {
            executeQueryOne(sql = sql, params = params, mapper = mapper)
        } catch (e: PgException) {
            state = prev
            throw e
        } catch (e: Throwable) {
            state = State.Failed(e)
            throw e
        } finally {
            if (state == State.InQuery) {
                state = prev
            }
        }
    }

    override suspend fun execute(
        sql: String,
        params: List<Any?>,
    ): Long = mutex.withLock {
        check(state == State.Ready || state == State.InTransaction) {
            "Connection not ready (state=$state)"
        }

        val prev = state
        state = State.InQuery

        try {
            executeCommand(sql, params)
        } catch (e: PgException) {
            state = prev
            throw e
        } catch (e: Throwable) {
            state = State.Failed(e)
            throw e
        } finally {
            if (state == State.InQuery) {
                state = prev
            }
        }
    }

    override suspend fun <T> executeReturning(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T
    ): List<T> = mutex.withLock {
        check(state == State.Ready || state == State.InTransaction) {
            "Connection is not ready for queries (state = $state)"
        }

        val prev = state
        state = State.InQuery
        try {
            executeQuery(sql = sql, params = params, mapper = mapper)
        } catch (e: PgException) {
            state = prev
            throw e
        } catch (e: Throwable) {
            state = State.Failed(e)
            throw e
        } finally {
            if (state == State.InQuery) {
                state = prev
            }
        }
    }

    /**
     * Returns a cold [Flow] backed by a wire-level cursor. The connection must have been
     * exclusively borrowed from the pool via [ConnectionPool.borrowConnection]; it is NOT
     * protected by the internal mutex so that [kotlinx.coroutines.flow.FlowCollector.emit]
     * can suspend freely without holding the lock.
     *
     * @param sql       SQL query string with positional parameters.
     * @param params    Query parameter values.
     * @param chunkSize Number of rows to fetch per `Execute` round-trip.
     * @param mapper    Row transformation function.
     */
    override fun <T> queryFlow(
        sql: String,
        params: List<Any?>,
        chunkSize: Int,
        mapper: (Row) -> T
    ): Flow<T> = flow {
        check(state == State.Ready || state == State.InTransaction) {
            "Connection is not ready for queries (state = $state)"
        }

        val prev = state
        state = State.InQuery
        try {
            sendExtendedQuery(sql = sql, params = params, maxRows = chunkSize)

            var rowDescription: PgMessage.RowDescription? = null

            loop@ while (true) {
                when (val msg = PgMessageReader.read(readChannel)) {
                    is PgMessage.ParseComplete,
                    is PgMessage.BindComplete,
                    is PgMessage.ParameterDescription -> Unit
                    is PgMessage.RowDescription -> rowDescription = msg
                    is PgMessage.DataRow -> {
                        val desc = checkNotNull(rowDescription) {
                            "DataRow received before RowDescription"
                        }
                        emit(mapper(Row(desc, msg, registry)))
                    }
                    is PgMessage.PortalSuspended -> {
                        // Fetch the next chunk
                        val bytes = PgMessageWriter.encode(PgMessage.Execute(maxRows = chunkSize)) +
                                PgMessageWriter.encode(PgMessage.Sync)
                        writeChannel.writeFully(bytes)
                        writeChannel.flush()
                    }
                    is PgMessage.CommandComplete -> Unit
                    is PgMessage.ReadyForQuery -> {
                        updateTransactionState(msg.status)
                        break@loop
                    }
                    is PgMessage.ErrorResponse -> {
                        drainUntilReady()
                        throw msg.toException(sql)
                    }
                    is PgMessage.NoticeResponse -> Unit
                    is PgMessage.NotificationResponse -> Unit
                    else -> error("Unexpected message during queryFlow: ${msg::class.simpleName}")
                }
            }
        } catch (e: PgException) {
            state = prev
            throw e
        } catch (e: Throwable) {
            state = State.Failed(e)
            throw e
        } finally {
            if (state == State.InQuery) {
                state = prev
            }
        }
    }

    /** Sends `BEGIN` to start an explicit transaction. The connection must be in [State.Ready]. */
    suspend fun beginTransaction()    { execute("BEGIN") }

    /** Sends `COMMIT` to commit the current transaction. */
    suspend fun commitTransaction()   { execute("COMMIT") }

    /** Sends `ROLLBACK` to abort the current transaction. */
    suspend fun rollbackTransaction() { execute("ROLLBACK") }

    /**
     * Sends a `Terminate` message to the server and closes the underlying TCP socket and
     * selector manager. After calling this method the connection must not be used again.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    suspend fun close() {
        val alreadyClosing = mutex.withLock {
            if (state == State.Closing) return@withLock true
            state = State.Closing
            false
        }
        if (alreadyClosing) return

        runCatching { PgMessageWriter.write(writeChannel, PgMessage.Terminate) }
        runCatching { socket.close() }
        runCatching { selectorManager.close() }
    }

    /**
     * Perpetual receive loop that dispatches [PgNotification] messages.
     *
     * This method suspends indefinitely and is designed for a *dedicated* listen-only
     * connection (see `PgListener`).  It must be the only active reader on this connection
     * and is NOT protected by the internal mutex.
     *
     * Silently discards protocol responses that arrive as side-effects of concurrent
     * `LISTEN`/`UNLISTEN` commands (`ParseComplete`, `BindComplete`, `NoData`,
     * `CommandComplete`, `ReadyForQuery`, `NoticeResponse`, `ParameterStatus`).
     * Any other unexpected message type terminates the loop.
     *
     * The loop exits cleanly on coroutine cancellation.
     */
    suspend fun notificationLoop(onNotification: suspend (PgNotification) -> Unit) {
        while (true) {
            when (val msg = PgMessageReader.read(readChannel)) {
                is PgMessage.NotificationResponse ->
                    onNotification(PgNotification(msg.processId, msg.channel, msg.payload))
                is PgMessage.NoticeResponse,
                is PgMessage.ParameterStatus,
                is PgMessage.ParseComplete,
                is PgMessage.BindComplete,
                is PgMessage.NoData,
                is PgMessage.CommandComplete,
                is PgMessage.ReadyForQuery -> Unit
                else -> break
            }
        }
    }

    private suspend fun <T> executeQuery(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T
    ): List<T> {
        sendExtendedQuery(sql = sql, params = params)

        val results = mutableListOf<T>()
        var rowDescription: PgMessage.RowDescription? = null

        loop@ while (true) {
            when (val msg = PgMessageReader.read(readChannel)) {
                is PgMessage.ParseComplete,
                is PgMessage.BindComplete,
                is PgMessage.ParameterDescription -> Unit
                is PgMessage.RowDescription -> rowDescription = msg
                is PgMessage.DataRow -> {
                    val desc = checkNotNull(rowDescription) {
                        "DataRow received before RowDescription"
                    }
                    results += mapper(Row(desc, msg, registry))
                }
                is PgMessage.CommandComplete -> Unit
                is PgMessage.ReadyForQuery -> {
                    updateTransactionState(msg.status)
                    break@loop
                }
                is PgMessage.ErrorResponse -> {
                    drainUntilReady()
                    throw msg.toException(sql)
                }
                is PgMessage.NoticeResponse -> Unit
                is PgMessage.NotificationResponse -> Unit
                else -> error("Unexpected message during query: ${msg::class.simpleName}")
            }
        }

        return results
    }

    private suspend fun <T> executeQueryOne(
        sql: String,
        params: List<Any?>,
        mapper: (Row) -> T
    ): T? {
        // Send Execute(maxRows=1) so the server only streams a single row
        sendExtendedQuery(sql = sql, params = params, maxRows = 1)

        var result: T? = null
        var rowDescription: PgMessage.RowDescription? = null

        loop@ while (true) {
            when (val msg = PgMessageReader.read(readChannel)) {
                is PgMessage.ParseComplete,
                is PgMessage.BindComplete,
                is PgMessage.ParameterDescription -> Unit
                is PgMessage.RowDescription -> rowDescription = msg
                is PgMessage.DataRow -> {
                    if (result == null) {
                        val desc = checkNotNull(rowDescription) {
                            "DataRow received before RowDescription"
                        }
                        result = mapper(Row(desc, msg, registry))
                    }
                    // Ignore any further DataRows (safety net; shouldn't occur with maxRows=1)
                }
                // Portal suspended after 1 row — the Sync we sent will produce ReadyForQuery
                is PgMessage.PortalSuspended -> Unit
                is PgMessage.CommandComplete -> Unit
                is PgMessage.ReadyForQuery -> {
                    updateTransactionState(msg.status)
                    break@loop
                }
                is PgMessage.ErrorResponse -> {
                    drainUntilReady()
                    throw msg.toException(sql)
                }
                is PgMessage.NoticeResponse -> Unit
                is PgMessage.NotificationResponse -> Unit
                else -> error("Unexpected message during queryOne: ${msg::class.simpleName}")
            }
        }

        return result
    }

    private suspend fun executeCommand(
        sql: String,
        params: List<Any?>,
    ): Long {
        sendExtendedQuery(sql, params)

        var rowsAffected = 0L

        loop@ while (true) {
            when (val msg = PgMessageReader.read(readChannel)) {
                is PgMessage.ParseComplete        -> Unit
                is PgMessage.BindComplete         -> Unit
                is PgMessage.ParameterDescription -> Unit
                is PgMessage.NoData               -> Unit
                is PgMessage.CommandComplete      -> rowsAffected = msg.rowsAffected
                is PgMessage.ReadyForQuery        -> {
                    updateTransactionState(msg.status)
                    break@loop
                }
                is PgMessage.ErrorResponse        -> {
                    drainUntilReady()
                    throw msg.toException(sql)
                }
                is PgMessage.NoticeResponse       -> Unit
                is PgMessage.NotificationResponse -> Unit
                else -> error("Unexpected message during execute: ${msg::class.simpleName}")
            }
        }

        return rowsAffected
    }

    // Parse + Bind + Describe(Portal) + Execute(maxRows) + Sync in a single flush
    private suspend fun sendExtendedQuery(sql: String, params: List<Any?>, maxRows: Int = 0) {
        val resolved  = params.map { it.toParam() }
        val encoded   = resolved.map { it.encode() }
        val paramOids = resolved.map { it.oid }

        val bytes = PgMessageWriter.encode(PgMessage.Parse(sql = sql, paramTypeOids = paramOids)) +
                PgMessageWriter.encode(PgMessage.Bind(params = encoded, resultFormat = ResultFormat.BINARY)) +
                PgMessageWriter.encode(PgMessage.Describe(target = DescribeTarget.PORTAL)) +
                PgMessageWriter.encode(PgMessage.Execute(maxRows = maxRows)) +
                PgMessageWriter.encode(PgMessage.Sync)

        writeChannel.writeFully(bytes)
        writeChannel.flush()
    }

    // Consume messages until ReadyForQuery — needed after an error to leave the channel clean
    private suspend fun drainUntilReady() {
        try {
            while (true) {
                when (val msg = PgMessageReader.read(readChannel)) {
                    is PgMessage.ReadyForQuery -> { updateTransactionState(msg.status); break }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            state = State.Failed(e)
            throw e
        }
    }

    private fun updateTransactionState(status: TransactionStatus) {
        state = when (status) {
            TransactionStatus.IDLE           -> State.Ready
            TransactionStatus.IN_TRANSACTION,
            TransactionStatus.FAILED         -> State.InTransaction
        }
    }

    private suspend fun performStartup() {
        state = State.Authenticating

        PgMessageWriter.write(writeChannel, PgMessage.StartupMessage(
            username = config.username,
            database = config.database,
            params   = mapOf(
                "client_encoding"  to "UTF8",
                "application_name" to config.applicationName,
            ),
        ))

        loop@ while (true) {
            when (val msg = PgMessageReader.read(readChannel)) {
                is PgMessage.Authentication  -> handleAuth(msg)
                is PgMessage.ParameterStatus -> serverParams[msg.name] = msg.value
                is PgMessage.BackendKeyData  -> { processId = msg.processId; secretKey = msg.secretKey }
                is PgMessage.ReadyForQuery   -> { state = State.Ready; break@loop }
                is PgMessage.ErrorResponse   -> throw msg.toException()
                is PgMessage.NoticeResponse  -> Unit
                else -> error("Unexpected message during startup: ${msg::class.simpleName}")
            }
        }
    }

    private suspend fun handleAuth(msg: PgMessage.Authentication) = when (msg) {
        is PgMessage.Authentication.Ok      -> Unit
        is PgMessage.Authentication.MD5     -> {
            val hash = md5Auth(config.username, config.password, msg.salt)
            PgMessageWriter.write(writeChannel, PgMessage.PasswordMessage(hash))
        }
        is PgMessage.Authentication.SASL    -> {
            val mechanism = msg.mechanisms.firstOrNull { it == "SCRAM-SHA-256" }
                ?: error("No supported SASL mechanism. Server offers: ${msg.mechanisms}")
            performScram(mechanism)
        }
        is PgMessage.Authentication.SASLContinue,
        is PgMessage.Authentication.SASLFinal ->
            error("Unexpected SASL message outside SCRAM flow: ${msg::class.simpleName}")
        is PgMessage.Authentication.Unknown ->
            error("Unsupported auth type ${msg.authType}. Cardio supports MD5 and SCRAM-SHA-256.")
    }

    private fun md5Auth(username: String, password: String, salt: ByteArray): String {
        val inner = md5Hex((password + username).toByteArray(Charsets.UTF_8))
        val outer = md5Hex(inner.toByteArray(Charsets.UTF_8) + salt)
        return "md5$outer"
    }

    private fun md5Hex(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }

    private suspend fun performScram(mechanism: String) {
        // ── Step 1: client-first-message ────────────────────────────────────
        val clientNonce            = generateNonce()
        val gs2Header              = "n,,"
        val clientFirstMessageBare = "n=,r=$clientNonce"
        val clientFirstMessage     = gs2Header + clientFirstMessageBare

        PgMessageWriter.write(writeChannel, PgMessage.SaslInitialResponse(
            mechanism          = mechanism,
            clientFirstMessage = clientFirstMessage.toByteArray(Charsets.UTF_8),
        ))

        // ── Step 2: server-first-message ────────────────────────────────────
        val serverFirst = when (val msg = PgMessageReader.read(readChannel)) {
            is PgMessage.Authentication.SASLContinue -> msg.data.toString(Charsets.UTF_8)
            is PgMessage.ErrorResponse               -> throw msg.toException()
            else -> error("Expected SASLContinue, got ${msg::class.simpleName}")
        }

        val serverAttrs  = parseScramParams(serverFirst)
        val serverNonce  = serverAttrs["r"] ?: error("SCRAM: missing 'r'")
        val salt         = java.util.Base64.getDecoder()
            .decode(serverAttrs["s"] ?: error("SCRAM: missing 's'"))
        val iterations   = (serverAttrs["i"] ?: error("SCRAM: missing 'i'")).toInt()

        check(serverNonce.startsWith(clientNonce)) {
            "SCRAM: server nonce doesn't start with client nonce — possible MITM"
        }

        // ── Step 3: client-final-message ────────────────────────────────────
        val channelBinding          = java.util.Base64.getEncoder()
            .encodeToString(gs2Header.toByteArray(Charsets.UTF_8))
        val clientFinalWithoutProof = "c=$channelBinding,r=$serverNonce"
        val authMessage             = "$clientFirstMessageBare,$serverFirst,$clientFinalWithoutProof"

        val saltedPassword  = hi(config.password, salt, iterations)
        val clientKey       = hmacSha256(saltedPassword, "Client Key")
        val storedKey       = sha256(clientKey)
        val clientSignature = hmacSha256(storedKey, authMessage)
        val clientProof     = xor(clientKey, clientSignature)
        val serverKey       = hmacSha256(saltedPassword, "Server Key")
        val serverSignature = hmacSha256(serverKey, authMessage)

        val proof = java.util.Base64.getEncoder().encodeToString(clientProof)
        PgMessageWriter.write(writeChannel, PgMessage.SaslResponse(
            clientFinalMessage = "$clientFinalWithoutProof,p=$proof".toByteArray(Charsets.UTF_8),
        ))

        // ── Step 4: server-final — verify server signature ──────────────────
        when (val msg = PgMessageReader.read(readChannel)) {
            is PgMessage.Authentication.SASLFinal -> {
                val finalAttrs = parseScramParams(msg.data.toString(Charsets.UTF_8))
                val serverSig  = java.util.Base64.getDecoder()
                    .decode(finalAttrs["v"] ?: error("SCRAM: missing 'v'"))
                check(serverSig.contentEquals(serverSignature)) {
                    "SCRAM: server signature mismatch — possible MITM attack"
                }
            }
            is PgMessage.ErrorResponse -> throw msg.toException()
            else -> error("Expected SASLFinal, got ${msg::class.simpleName}")
        }

        // ── Step 5: Authentication.Ok ────────────────────────────────────────
        when (val msg = PgMessageReader.read(readChannel)) {
            is PgMessage.Authentication.Ok -> Unit
            is PgMessage.ErrorResponse     -> throw msg.toException()
            else -> error("Expected AuthenticationOk, got ${msg::class.simpleName}")
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(24)
        SECURE_RANDOM.nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /** Hi() = PBKDF2WithHmacSHA256 */
    private fun hi(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec    = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun hmacSha256(key: ByteArray, data: String)  = hmacSha256(key, data.toByteArray(Charsets.UTF_8))
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "XOR: array sizes differ (${a.size} vs ${b.size})" }
        return ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    private fun parseScramParams(input: String): Map<String, String> =
        input.split(",").associate { part ->
            val eq = part.indexOf('=')
            if (eq == -1) part to "" else part.substring(0, eq) to part.substring(eq + 1)
        }

    companion object {
        /** Shared, thread-safe random source — reused across all connections. */
        private val SECURE_RANDOM = SecureRandom()

        /** IPv4 address pattern: four dot-separated octets, each in 0–255. */
        private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /** PostgreSQL SSLRequest wire-protocol bytes (length=8, code=0x04D2162F). */
        private val SSL_REQUEST_BYTES = byteArrayOf(
            0x00, 0x00, 0x00, 0x08,
            0x04, 0xD2.toByte(), 0x16, 0x2F
        )

        /** Trust-all manager used by PREFER and REQUIRE — shared singleton, no state. */
        private val TRUST_ALL_MANAGER: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        /**
         * Opens a connection to the PostgreSQL server described by [config], performs SSL
         * negotiation (if required), and completes the startup/authentication handshake.
         *
         * Returns the ready-to-use [Connection] on success.
         *
         * @param config   Connection parameters (host, port, credentials, SSL mode, …).
         * @param registry Codec registry used to encode parameters and decode result rows.
         *                 Defaults to [TypeCodecRegistry.Default].
         * @param context  [kotlin.coroutines.CoroutineContext] for the Ktor selector manager.
         *                 Defaults to [kotlinx.coroutines.Dispatchers.IO].
         * @throws PgConnectException if the TCP connection or startup handshake fails or times out.
         * @throws PgSslException     if SSL negotiation fails (server declined a required mode,
         *                            certificate validation failed, or hostname mismatch).
         * @throws io.github.blad3mak3r.cardio.protocol.PgException if the server rejects the connection (wrong credentials, etc.).
         */
        suspend fun connect(
            config: Configuration,
            registry: TypeCodecRegistry = TypeCodecRegistry.Default,
            context: CoroutineContext = Dispatchers.IO
        ): Connection {
            val selectorManager = SelectorManager(context)

            val plainSocket = try {
                withTimeout(config.connectTimeoutMs) {
                    aSocket(selectorManager)
                        .tcp()
                        .connect(config.host, config.port) { keepAlive = true }
                }
            } catch (e: TimeoutCancellationException) {
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            } catch (e: CancellationException) {
                runCatching { selectorManager.close() }
                throw e
            } catch (e: Exception) {
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            }

            val (activeSocket, readChannel, writeChannel) = try {
                withTimeout(config.connectTimeoutMs) {
                    negotiateSsl(plainSocket, config, context)
                }
            } catch (e: TimeoutCancellationException) {
                runCatching { plainSocket.close() }
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            } catch (e: CancellationException) {
                runCatching { plainSocket.close() }
                runCatching { selectorManager.close() }
                throw e
            } catch (e: Exception) {
                runCatching { plainSocket.close() }
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            }

            val conn = Connection(
                config, selectorManager, activeSocket, readChannel, writeChannel, registry
            )

            try {
                withTimeout(config.connectTimeoutMs) {
                    conn.performStartup()
                }
            } catch (e: TimeoutCancellationException) {
                runCatching { activeSocket.close() }
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            } catch (e: CancellationException) {
                runCatching { activeSocket.close() }
                runCatching { selectorManager.close() }
                throw e
            } catch (e: Exception) {
                runCatching { activeSocket.close() }
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            }

            return conn
        }

        /**
         * Handles the PostgreSQL SSL negotiation handshake and, when required, upgrades
         * the plain TCP [socket] to a TLS connection.
         *
         * Returns a triple of (active socket, read channel, write channel) that callers
         * should use for all subsequent I/O.  The plain socket is returned unchanged when
         * [Configuration.sslMode] is [SslMode.DISABLE] or when [SslMode.PREFER] is used
         * and the server declines TLS.
         */
        private suspend fun negotiateSsl(
            socket: Socket,
            config: Configuration,
            context: CoroutineContext
        ): Triple<Socket, ByteReadChannel, ByteWriteChannel> {
            if (config.sslMode == SslMode.DISABLE) {
                return Triple(
                    socket,
                    socket.openReadChannel(),
                    socket.openWriteChannel(autoFlush = false)
                )
            }

            // Send the PostgreSQL SSLRequest message before opening permanent channels.
            // The request consists of two big-endian Int32 values: length=8, code=80877103.
            val plainWrite = socket.openWriteChannel(autoFlush = false)
            val plainRead  = socket.openReadChannel()

            plainWrite.writeFully(SSL_REQUEST_BYTES)
            plainWrite.flush()

            val serverResponse = plainRead.readByte().toInt().toChar()

            return when (serverResponse) {
                'S' -> {
                    // Server accepted SSL — perform the TLS handshake.
                    // plainRead/plainWrite are abandoned here: they hold no OS resources (the
                    // socket owns the FD). Explicitly closing them risks a TCP half-close before
                    // the TLS handshake; socket.close() in the caller's error paths is the
                    // single resource-release point.
                    val trustManager = buildTrustManager(config)
                    val tlsSocket = socket.tls(context) {
                        this.trustManager = trustManager
                        // SNI must not be set for IP address literals (RFC 6066 §3).
                        if (!isIpAddress(config.host)) {
                            this.serverName = config.host
                        }
                    }
                    Triple(
                        tlsSocket,
                        tlsSocket.openReadChannel(),
                        tlsSocket.openWriteChannel(autoFlush = false)
                    )
                }
                'N' -> {
                    // Server declined SSL.
                    if (config.sslMode == SslMode.PREFER) {
                        // Gracefully fall back to plain-text on the already-open channels.
                        Triple(socket, plainRead, plainWrite)
                    } else {
                        throw PgSslException(
                            "Server does not support SSL/TLS but sslMode=${config.sslMode} requires it"
                        )
                    }
                }
                else -> throw PgSslException(
                    "Unexpected SSL negotiation response from server: '$serverResponse' " +
                    "(0x${serverResponse.code.toString(16)})"
                )
            }
        }

        /**
         * Builds the appropriate [X509TrustManager] for the given [Configuration.sslMode].
         *
         * | Mode         | Behaviour                                              |
         * |--------------|--------------------------------------------------------|
         * | REQUIRE      | Trust all certificates — no validation whatsoever.    |
         * | VERIFY_CA    | Validate certificate chain; skip hostname check.      |
         * | VERIFY_FULL  | Validate certificate chain AND verify hostname.       |
         */
        private fun buildTrustManager(config: Configuration): X509TrustManager =
            when (config.sslMode) {
                SslMode.REQUIRE     -> TRUST_ALL_MANAGER
                SslMode.VERIFY_CA   -> caVerifyManager(config.sslRootCert)
                SslMode.VERIFY_FULL -> caAndHostnameVerifyManager(config.sslRootCert, config.host)
                // PREFER: trust-all — server cert is not verified on opportunistic TLS.
                else                -> TRUST_ALL_MANAGER
            }

        /**
         * A trust manager that validates the server's certificate chain against the
         * provided [sslRootCert] PEM bytes.  When [sslRootCert] is `null`, the JVM's
         * default trust store is used.
         *
         * Supports PEM files that contain multiple concatenated certificates (e.g. a
         * CA bundle or an intermediate + root chain); all certificates are added to the
         * in-memory key store.
         */
        private fun caVerifyManager(sslRootCert: ByteArray?): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            if (sslRootCert != null) {
                val cf = CertificateFactory.getInstance("X.509")
                val certs = try {
                    cf.generateCertificates(ByteArrayInputStream(sslRootCert))
                } catch (e: CertificateException) {
                    throw PgSslException(
                        "Failed to parse sslRootCert: the data is not valid PEM/DER X.509 (${e.message})", e
                    )
                }
                if (certs.isEmpty()) throw PgSslException("sslRootCert contains no certificates")
                val ks   = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    certs.forEachIndexed { index, cert ->
                        setCertificateEntry("pg-root-ca-$index", cert)
                    }
                }
                tmf.init(ks)
            } else {
                val nullKeyStore: KeyStore? = null
                tmf.init(nullKeyStore)
            }
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: throw PgSslException("TrustManagerFactory returned no X509TrustManager")
        }

        /**
         * A trust manager that validates the server's certificate chain AND verifies
         * that the leaf certificate's hostname matches [hostname].
         *
         * Hostname matching follows RFC 2818 / RFC 6125:
         *  1. Subject Alternative Names (dNSName entries) are checked first.
         *  2. The Common Name (CN) of the Subject DN is used as a fallback.
         *  3. Wildcard certificates (e.g. `*.example.com`) are supported.
         */
        private fun caAndHostnameVerifyManager(
            sslRootCert: ByteArray?,
            hostname: String
        ): X509TrustManager {
            val inner = caVerifyManager(sslRootCert)
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    inner.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw CertificateException("Certificate chain is empty")
                    inner.checkServerTrusted(chain, authType)
                    try {
                        verifyHostname(hostname, chain[0])
                    } catch (e: SSLPeerUnverifiedException) {
                        throw CertificateException("Hostname verification failed: ${e.message}", e)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = inner.getAcceptedIssuers()
            }
        }

        /**
         * Verifies that [hostname] matches the names declared in the leaf [certificate].
         *
         * Follows RFC 2818 §3.1 and RFC 6125:
         * - When [hostname] is an **IP address**, only iPAddress SANs (type 7) are
         *   authoritative; CN fallback is explicitly prohibited.
         * - When [hostname] is a **DNS name**, Subject Alternative Names (dNSName, type 2)
         *   are checked first; the Subject CN is used as a fallback.
         * - Wildcard patterns (e.g. `*.example.com`) are supported for DNS names.
         *
         * @throws SSLPeerUnverifiedException if no name in the certificate matches [hostname].
         */
        private fun verifyHostname(hostname: String, certificate: X509Certificate) {
            val ipConn = isIpAddress(hostname)
            val sans   = try {
                certificate.subjectAlternativeNames
            } catch (e: Exception) {
                throw SSLPeerUnverifiedException(
                    "Failed to read SAN extensions from certificate: ${e.message}"
                ).also { it.initCause(e) }
            }

            if (sans != null) {
                if (ipConn) {
                    // RFC 2818 §3.1: for IP addresses only iPAddress SANs (type 7) apply.
                    val ipSans = sans.filter { (it[0] as? Int) == 7 }
                        .mapNotNull { it[1] as? String }
                    if (ipSans.isNotEmpty()) {
                        if (ipSans.any { ipAddressesMatch(hostname, it) }) return
                        throw SSLPeerUnverifiedException(
                            "Host IP '$hostname' does not match any certificate iPAddress SAN: " +
                            ipSans.joinToString()
                        )
                    }
                    // No iPAddress SAN at all — CN fallback is not valid for IPs.
                    throw SSLPeerUnverifiedException(
                        "Certificate has no iPAddress SAN entries for IP host '$hostname'"
                    )
                } else {
                    val dnsNames = sans
                        .filter { (it[0] as? Int) == 2 } // 2 = dNSName
                        .mapNotNull { it[1] as? String }

                    if (dnsNames.isNotEmpty()) {
                        if (dnsNames.any { hostnameMatches(hostname, it) }) return
                        throw SSLPeerUnverifiedException(
                            "Hostname '$hostname' does not match any SAN dNSName: " +
                            dnsNames.joinToString()
                        )
                    }
                }
            }

            // IP addresses must not fall back to CN (RFC 2818 §3.1).
            if (ipConn) {
                throw SSLPeerUnverifiedException(
                    "Certificate has no SAN entries; CN fallback is not valid for IP host '$hostname'"
                )
            }

            // Fallback: CN from Subject DN (deprecated per RFC 2818 §3.1 but still common).
            val cn = extractCN(certificate.subjectX500Principal.name)
                ?: throw SSLPeerUnverifiedException(
                    "Certificate for '$hostname' has neither SAN entries nor a CN field"
                )

            if (!hostnameMatches(hostname, cn)) {
                throw SSLPeerUnverifiedException(
                    "Hostname '$hostname' does not match certificate CN '$cn'"
                )
            }
        }

        /**
         * Returns `true` when [hostname] matches [pattern].
         *
         * Matching is case-insensitive.  A pattern beginning with `*.` matches exactly
         * one DNS label, so `*.example.com` matches `foo.example.com` but **not**
         * `bar.foo.example.com` or `example.com`.
         */
        private fun hostnameMatches(hostname: String, pattern: String): Boolean {
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1) // ".example.com"
                if (!hostname.endsWith(suffix, ignoreCase = true)) return false
                val label = hostname.substring(0, hostname.length - suffix.length)
                return label.isNotEmpty() && '.' !in label
            }
            return hostname.equals(pattern, ignoreCase = true)
        }

        /**
         * Returns `true` when [hostname] appears to be an IP address literal (IPv4 or IPv6).
         *
         * Detection is purely lexical to avoid unintended DNS resolution:
         * - IPv4: four dot-separated numeric groups, each octet in 0–255 (e.g. `192.168.1.1`).
         * - IPv6: contains a colon, optionally surrounded by brackets (e.g. `[::1]`).
         */
        private fun isIpAddress(hostname: String): Boolean {
            val ipv4Match = IPV4_REGEX.matchEntire(hostname)
            if (ipv4Match != null) {
                return ipv4Match.groupValues.drop(1).all { it.toInt() in 0..255 }
            }
            val stripped = hostname.removePrefix("[").removeSuffix("]")
            return ':' in stripped
        }

        /**
         * Returns `true` when [host] and [certIp] represent the same IP address.
         *
         * Delegates to [InetAddress] so that different textual representations of the
         * same IPv6 address (e.g. `::1` vs `0:0:0:0:0:0:0:1`) compare as equal.
         * For IP literal strings [InetAddress.getByName] never performs DNS lookups.
         *
         * Bracketed IPv6 literals (e.g. `[::1]`) are normalised first because
         * [InetAddress.getByName] expects the raw address literal, not the URI-style
         * bracketed form.
         */
        private fun ipAddressesMatch(host: String, certIp: String): Boolean = try {
            val normalizedHost   = host.removePrefix("[").removeSuffix("]")
            val normalizedCertIp = certIp.removePrefix("[").removeSuffix("]")
            InetAddress.getByName(normalizedHost) == InetAddress.getByName(normalizedCertIp)
        } catch (_: Exception) {
            false
        }

        /**
         * Extracts the first CN value from an RFC 2253 Distinguished Name string
         * (as returned by [javax.security.auth.x500.X500Principal.getName]).
         *
         * Uses [LdapName] for correct RFC 2253 parsing, including escaped commas and
         * other special characters that would break a naive `split(",")` approach.
         */
        private fun extractCN(distinguishedName: String): String? = try {
            LdapName(distinguishedName).rdns
                .firstNotNullOfOrNull { rdn ->
                    rdn.toAttributes().get("cn")?.get()?.toString()
                }
        } catch (_: javax.naming.InvalidNameException) {
            // Malformed DN — treat as "no CN found" and fall through to the
            // SSLPeerUnverifiedException thrown by the caller.
            null
        } catch (e: Exception) {
            throw SSLPeerUnverifiedException(
                "Failed to parse certificate Distinguished Name: ${e.message}"
            ).also { it.initCause(e) }
        }
    }
}
