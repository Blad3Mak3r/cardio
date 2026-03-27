package io.github.blad3mak3r.cardio.protocol.connection

import io.github.blad3mak3r.cardio.protocol.DatabaseOperations
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext

class Connection private constructor(
    private val config: Configuration,
    private val selectorManager: SelectorManager,
    private val socket: Socket,
    private val readChannel: ByteReadChannel,
    private val writeChannel: ByteWriteChannel,
    internal val registry: TypeCodecRegistry
) : DatabaseOperations {
    enum class SslMode { DISABLE, PREFER, REQUIRE}

    data class Configuration(
        val host: String = "localhost",
        val port: Int = 5432,
        val database: String,
        val username: String,
        val password: String,
        val sslMode: SslMode = SslMode.DISABLE,
        val connectTimeoutMs: Long = 5_000L,
        val applicationName: String = "cardio-pg-client"
    )

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

    var processId: Int = 0
        private set

    var secretKey: Int = 0
        private set

    val serverParams: MutableMap<String, String> = mutableMapOf()

    val isReady: Boolean
        get() = state == State.Ready

    val isFailed: Boolean
        get() = state is State.Failed

    override suspend fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (Row) -> T
    ): List<T> = mutex.withLock {
        check(state == State.Ready || state == State.InTransaction) {
            "Connection is not ready for queries (state = ${state})"
        }

        val prev = state
        state = State.InQuery
        try {
            executeQuery(sql, params, mapper)
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

    override suspend fun execute(
        sql: String,
        vararg params: Any?
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

    suspend fun beginTransaction() {
        execute("BEGIN")
        state = State.InTransaction
    }

    suspend fun commitTransaction() {
        execute("COMMIT")
        state = State.Ready
    }

    suspend fun rollbackTransaction() {
        execute("ROLLBACK")
        state = State.Ready
    }

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

    private suspend fun <T> executeQuery(
        sql: String,
        params: Array<out Any?>,
        mapper: (Row) -> T
    ): List<T> {
        sendExtendedQuery(sql, params)

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
                    throw msg.toException()
                }
                is PgMessage.NoticeResponse -> Unit
                else -> error("Unexpected message during query: ${msg::class.simpleName}")
            }
        }

        return results
    }

    private suspend fun executeCommand(
        sql: String,
        params: Array<out Any?>,
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
                    throw msg.toException()
                }
                is PgMessage.NoticeResponse       -> Unit
                else -> error("Unexpected message during execute: ${msg::class.simpleName}")
            }
        }

        return rowsAffected
    }

    // Parse + Bind + Describe(Portal) + Execute + Sync en un solo flush
    private suspend fun sendExtendedQuery(sql: String, params: Array<out Any?>) {
        val resolved  = params.map { it.toParam() }
        val encoded   = resolved.map { it.encode() }
        val paramOids = resolved.map { it.oid }

        val bytes = PgMessageWriter.encode(PgMessage.Parse(sql = sql, paramTypeOids = paramOids)) +
                PgMessageWriter.encode(PgMessage.Bind(params = encoded, resultFormat = ResultFormat.BINARY)) +
                PgMessageWriter.encode(PgMessage.Describe(target = DescribeTarget.PORTAL)) +
                PgMessageWriter.encode(PgMessage.Execute()) +
                PgMessageWriter.encode(PgMessage.Sync)

        writeChannel.writeFully(bytes)
        writeChannel.flush()
    }

    // Consume mensajes hasta ReadyForQuery — necesario tras un error
    // para dejar el canal en estado limpio
    private suspend fun drainUntilReady() {
        while (true) {
            val msg = PgMessageReader.read(readChannel)
            if (msg is PgMessage.ReadyForQuery) {
                updateTransactionState(msg.status)
                break
            }
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
        // ── Paso 1: client-first-message ────────────────────────────────────
        val clientNonce            = generateNonce()
        val gs2Header              = "n,,"
        val clientFirstMessageBare = "n=,r=$clientNonce"
        val clientFirstMessage     = gs2Header + clientFirstMessageBare

        PgMessageWriter.write(writeChannel, PgMessage.SaslInitialResponse(
            mechanism          = mechanism,
            clientFirstMessage = clientFirstMessage.toByteArray(Charsets.UTF_8),
        ))

        // ── Paso 2: server-first-message ────────────────────────────────────
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

        // ── Paso 3: client-final-message ────────────────────────────────────
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

        // ── Paso 4: server-final — verificar firma del servidor ──────────────
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

        // ── Paso 5: Authentication.Ok ────────────────────────────────────────
        when (val msg = PgMessageReader.read(readChannel)) {
            is PgMessage.Authentication.Ok -> Unit
            is PgMessage.ErrorResponse     -> throw msg.toException()
            else -> error("Expected AuthenticationOk, got ${msg::class.simpleName}")
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
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

    private fun xor(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

    private fun parseScramParams(input: String): Map<String, String> =
        input.split(",").associate { part ->
            val eq = part.indexOf('=')
            if (eq == -1) part to "" else part.substring(0, eq) to part.substring(eq + 1)
        }

    companion object {
        suspend fun connect(
            config: Configuration,
            registry: TypeCodecRegistry = TypeCodecRegistry.Default,
            context: CoroutineContext = Dispatchers.IO
        ): Connection {
            val selectorManager = SelectorManager(context)

            val socket = withTimeout(config.connectTimeoutMs) {
                aSocket(selectorManager)
                    .tcp()
                    .connect(config.host, config.port) { keepAlive = true }
            }

            val readChannel  = socket.openReadChannel()
            val writeChannel = socket.openWriteChannel(autoFlush = false)

            val conn = Connection(
                config, selectorManager, socket, readChannel, writeChannel, registry
            )

            try {
                conn.performStartup()
            } catch (e: Exception) {
                runCatching { socket.close() }
                runCatching { selectorManager.close() }
                throw PgConnectException(config.host, config.port, e)
            }

            return conn
        }
    }
}