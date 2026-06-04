package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.Row
import io.github.blad3mak3r.cardio.protocol.codec.TypeCodecRegistry
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import io.github.blad3mak3r.cardio.protocol.connection.ConnectionPool
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Main entry point for non-blocking PostgreSQL access.
 *
 * `Cardio` manages a [ConnectionPool] and exposes a high-level coroutine-based API
 * for executing queries, DML/DDL statements, and transactions.
 *
 * Obtain an instance via the [new] (or [newCustom]) factory function:
 * ```kotlin
 * val db = Cardio.new {
 *     host     = "localhost"
 *     database = "mydb"
 *     username = "user"
 *     password = "secret"
 * }
 * ```
 *
 * The factory probes the database and throws if the connection cannot be established.
 * After use, call [close] to release all connections and stop background tasks.
 *
 * @param pool The underlying [ConnectionPool] that manages physical connections.
 * @see CardioTransaction
 * @see CardioRepository
 */
open class Cardio(
    private val pool: ConnectionPool
) {

    /**
     * DSL-style configuration builder for [Cardio].
     *
     * All string properties that are logically required (`database`, `username`) are validated
     * when [buildPoolConfig] is called.  They default to empty strings to allow incremental
     * population (e.g. via the [url][io.github.blad3mak3r.cardio.core.url] extension).
     */
    class Configuration {

        /** Hostname or IP address of the PostgreSQL server. Defaults to `"localhost"`. */
        var host: String            = "localhost"
        /** TCP port of the PostgreSQL server. Defaults to `5432`. */
        var port: Int               = 5432
        /** Name of the database to connect to. Must not be blank. */
        var database: String        = ""
        /** PostgreSQL username. Must not be blank. */
        var username: String        = ""
        /** PostgreSQL password. */
        var password: String        = ""
        /** SSL/TLS mode. Defaults to [Connection.SslMode.DISABLE]. */
        var ssl: Connection.SslMode = Connection.SslMode.DISABLE
        /**
         * PEM-encoded CA certificate used for [Connection.SslMode.VERIFY_CA] and
         * [Connection.SslMode.VERIFY_FULL].  When `null` the JVM's default trust store
         * is used.  Ignored for [Connection.SslMode.DISABLE], [Connection.SslMode.PREFER],
         * and [Connection.SslMode.REQUIRE].
         */
        var sslRootCert: ByteArray? = null
        /** Value sent in the `application_name` startup parameter. Defaults to `"cardio-pg-client"`. */
        var applicationName: String = "cardio-pg-client"
        /** Maximum number of simultaneous connections in the pool. Defaults to `10`. */
        var maxSize: Int            = 10
        /** Minimum number of connections kept alive (warm-up / replenishment). Defaults to `2`. */
        var minSize: Int            = 2
        /** Maximum time a caller waits for a free connection. Defaults to 30 seconds. */
        var acquireTimeout: Duration = 30.seconds
        /** Time after which an idle connection is eligible for eviction. Defaults to 600 seconds. */
        var idleTimeout: Duration    = 600.seconds

        private var registry: TypeCodecRegistry = TypeCodecRegistry.Default

        /**
         * Customises the [TypeCodecRegistry] that all connections in the pool will use.
         *
         * [block] is applied to a copy of [TypeCodecRegistry.Default], so built-in codecs
         * are always available.  Typical use-case: registering a custom enum or domain codec.
         *
         * ```kotlin
         * Cardio.new {
         *     codecs { register(MyEnumCodec) }
         * }
         * ```
         */
        fun codecs(block: TypeCodecRegistry.() -> Unit) {
            registry = TypeCodecRegistry.Default.apply(block)
        }

        /** Builds the [ConnectionPool.Configuration] from the current property values. */
        fun buildPoolConfig() = ConnectionPool.Configuration(
            connect = Connection.Configuration(
                host            = host,
                port            = port,
                database        = database.ifBlank { error("Cardio: database must not be blank") },
                username        = username.ifBlank { error("Cardio: username must not be blank") },
                password        = password,
                sslMode         = ssl,
                sslRootCert     = sslRootCert?.copyOf(),
                applicationName = applicationName,
            ),
            maxSize        = maxSize,
            minSize        = minSize,
            acquireTimeout = acquireTimeout,
            idleTimeout    = idleTimeout,
            registry       = registry,
        )
    }

    /** Returns a point-in-time snapshot of the pool's operational statistics. */
    val stats: ConnectionPool.Stats
        get() = pool.stats

    /** Closes all connections and stops the background tasks of the underlying pool. */
    suspend fun close() = pool.close()

    /**
     * Executes [sql] and maps every result row to a value using [mapper].
     *
     * If called within an active [inTransaction] block, the query automatically runs on the
     * transaction's connection without requiring an explicit `tx` parameter.
     *
     * @param sql    PostgreSQL query using positional parameters (`$1`, `$2`, …).
     * @param params Parameter values in positional order.
     * @param mapper Transformation applied to each result row.
     * @return List of values produced by [mapper].
     */
    suspend fun <T> query(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T,
    ): List<T> {
        val txCtx = currentCoroutineContext()[CardioTransaction.Context]
        return if (txCtx != null) txCtx.transaction.query(sql, params, mapper)
        else pool.query(sql = sql, params = params, mapper = mapper)
    }

    /**
     * Executes [sql] and returns the first result row mapped by [mapper], or `null` if the
     * result set is empty.
     *
     * Optimized at the wire level — sends `Execute(maxRows = 1)` so the server streams at most
     * one row.  If called within an active [inTransaction] block, runs on the transaction's
     * connection automatically.
     *
     * @param sql    PostgreSQL query using positional parameters.
     * @param params Parameter values in positional order.
     * @param mapper Transformation applied to the first result row.
     * @return The mapped first row, or `null` if no rows were returned.
     */
    suspend fun <T> queryOne(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T,
    ): T? {
        val txCtx = currentCoroutineContext()[CardioTransaction.Context]
        return if (txCtx != null) txCtx.transaction.queryOne(sql, params, mapper)
        else pool.use { it.queryOne(sql = sql, params = params, mapper = mapper) }
    }

    /**
     * Executes [sql] as a DML/DDL statement and returns the number of affected rows.
     *
     * If called within an active [inTransaction] block, the statement automatically runs on
     * the transaction's connection.
     *
     * @param sql    PostgreSQL statement using positional parameters (`$1`, `$2`, …).
     * @param params Parameter values in positional order.
     * @return Number of rows affected.
     */
    suspend fun execute(
        sql: String,
        params: List<Any?> = emptyList(),
    ): Long {
        val txCtx = currentCoroutineContext()[CardioTransaction.Context]
        return if (txCtx != null) txCtx.transaction.execute(sql, params)
        else pool.execute(sql = sql, params = params)
    }

    /**
     * Executes a DML statement with a `RETURNING` clause and maps each returned row using [mapper].
     *
     * If called within an active [inTransaction] block, runs on the transaction's connection
     * automatically.
     *
     * @param sql    PostgreSQL statement with `RETURNING` using positional parameters.
     * @param params Parameter values in positional order.
     * @param mapper Transformation applied to each returned row.
     * @return List of values produced by [mapper], one per returned row.
     */
    suspend fun <T> executeReturning(
        sql: String,
        params: List<Any?> = emptyList(),
        mapper: (Row) -> T,
    ): List<T> {
        val txCtx = currentCoroutineContext()[CardioTransaction.Context]
        return if (txCtx != null) txCtx.transaction.executeReturning(sql, params, mapper)
        else pool.executeReturning(sql = sql, params = params, mapper = mapper)
    }

    /**
     * Returns a cold [Flow] that streams result rows one chunk at a time using a
     * wire-level cursor (`Execute(maxRows = chunkSize)`).
     *
     * Suitable for large result sets where loading all rows into memory at once is
     * undesirable.  A connection is exclusively held for the duration of collection.
     *
     * Note: this method does **not** participate in [inTransaction] auto-routing because
     * it is not a suspending function.
     *
     * @param sql       PostgreSQL query using positional parameters.
     * @param params    Query parameter values.
     * @param chunkSize Number of rows to fetch per `Execute` round-trip. Defaults to `100`.
     * @param mapper    Function applied to each [Row].
     * @return A cold [Flow] that emits mapped values as rows arrive from the server.
     */
    fun <T> queryFlow(
        sql: String,
        params: List<Any?> = emptyList(),
        chunkSize: Int = 100,
        mapper: (Row) -> T,
    ): Flow<T> = pool.queryFlow(sql = sql, params = params, chunkSize = chunkSize, mapper = mapper)

    /**
     * Creates a [PgListener] that reuses this instance's connection configuration.
     *
     * The listener starts with no active channel subscriptions; call [PgListener.listen]
     * to begin receiving notifications.  Use [listen] for the common create-and-subscribe
     * shorthand.
     *
     * The listener opens its own dedicated connection outside the pool and manages its
     * lifecycle independently of this [Cardio] instance.
     */
    fun newListener(): PgListener = PgListener.fromPool(pool.configuration)

    /**
     * Creates a [PgListener] that reuses this instance's connection configuration and
     * immediately subscribes to [channels].
     *
     * ```kotlin
     * val listener = db.listen("orders", "shipments")
     * listener.notifications.collect { n -> handleNotification(n) }
     * ```
     *
     * @param channels One or more PostgreSQL channel names to subscribe to.
     * @return The ready-to-collect [PgListener].
     */
    suspend fun listen(vararg channels: String): PgListener =
        newListener().also { it.listen(*channels) }

    /**
     * Sends a notification on [channel] with an optional [payload] using `pg_notify`.
     *
     * If called within an active [inTransaction] block, the notification is sent on the
     * transaction's connection and will only be visible to other sessions after the
     * transaction commits (PostgreSQL defers delivery until commit).
     *
     * @param channel Name of the channel to notify.
     * @param payload Arbitrary string payload, up to 8 000 bytes. Defaults to empty.
     */
    suspend fun notify(channel: String, payload: String = "") {
        val txCtx = currentCoroutineContext()[CardioTransaction.Context]
        if (txCtx != null)
            txCtx.transaction.query("SELECT pg_notify(\$1, \$2)", listOf(channel, payload)) { }
        else
            pool.query("SELECT pg_notify(\$1, \$2)", listOf(channel, payload)) { }
    }

    /**
     * Acquires a connection, begins a transaction, and runs [block] inside it.
     * Commits on success; rolls back automatically if [block] throws.
     *
     * Nested calls to [inTransaction] on the same coroutine (or child coroutines that inherit
     * the same [CoroutineContext][kotlin.coroutines.CoroutineContext]) automatically join the
     * existing transaction rather than starting a new one.
     *
     * Inside the block, calls to [query], [execute], [executeReturning], and [queryOne] on
     * this [Cardio] instance automatically route to the active transaction.
     *
     * @param block Suspending extension lambda on [CardioTransaction].
     * @return The value returned by [block].
     */
    suspend fun <T> inTransaction(block: suspend CardioTransaction.() -> T): T {
        val existing = currentCoroutineContext()[CardioTransaction.Context]
        if (existing != null) return existing.transaction.block()
        return pool.transaction { conn ->
            val tx = CardioTransaction(conn)
            withContext(CardioTransaction.Context(tx)) { tx.block() }
        }
    }

    /**
     * Acquires a connection and passes it (as a [CardioTransaction] wrapper) to [block].
     * Unlike [inTransaction], **no** `BEGIN`/`COMMIT`/`ROLLBACK` is sent automatically.
     * This is useful for operations that must run outside a transaction (e.g. `LISTEN`).
     *
     * @param block Suspending lambda that receives the raw connection handle.
     */
    suspend fun useConnection(block: suspend (CardioTransaction) -> Unit) =
        pool.use { conn -> block(CardioTransaction(conn)) }

    companion object {
        /**
         * Creates a [Cardio] instance, warms up the connection pool, and probes the database.
         *
         * @param block DSL lambda to configure the [Configuration].
         * @return A ready-to-use [Cardio] instance.
         * @throws io.github.blad3mak3r.cardio.protocol.connection.PgConnectException if the database cannot be reached.
         * @throws io.github.blad3mak3r.cardio.protocol.PgException if the credentials are rejected.
         */
        suspend fun new(block: Configuration.() -> Unit): Cardio {
            val config = Configuration().apply(block)
            val pool   = ConnectionPool(config.buildPoolConfig())
            pool.probe()
            return Cardio(pool)
        }

        /**
         * Creates an instance of a [Cardio] subclass [C] using reflection.
         *
         * The target class [C] must have a primary constructor that accepts a single
         * [ConnectionPool] argument.  The pool is warmed up and probed before the subclass
         * instance is created.
         *
         * If you prefer zero-reflection, use [new] and pass the pool to your subclass
         * constructor manually.
         *
         * @param block DSL lambda to configure the [Configuration].
         * @return A ready-to-use instance of [C].
         * @throws io.github.blad3mak3r.cardio.protocol.connection.PgConnectException if the database cannot be reached.
         * @throws io.github.blad3mak3r.cardio.protocol.PgException if the credentials are rejected.
         * @throws NoSuchMethodException if [C] does not have a constructor accepting [ConnectionPool].
         */
        suspend inline fun <reified C : Cardio> newCustom(noinline block: Configuration.() -> Unit): C {
            val config = Configuration().apply(block)
            val pool   = ConnectionPool(config.buildPoolConfig())
            pool.probe()
            return C::class.java
                .getDeclaredConstructor(ConnectionPool::class.java)
                .newInstance(pool)
        }
    }
}
