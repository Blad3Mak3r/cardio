# Cardio – AI Agent Guide

## Project Overview
Cardio is a Kotlin library for non-blocking PostgreSQL access using coroutines, plus an optional serialization add-on.

## Module Map

```
cardio-protocol      – Custom PostgreSQL wire protocol over Ktor sockets
cardio-core          – User-facing API on top of cardio-protocol (Cardio, CardioRepository, CardioTransaction)
cardio-serialization – Optional kotlinx.serialization bridge (depends on cardio-core)
```

**Dependency graph:** `cardio-serialization` → `cardio-core` → `cardio-protocol`

`cardio-core`, `cardio-protocol`, and `cardio-serialization` are published to Maven Central.

## Build & Test

```bash
./gradlew build                       # build all modules
./gradlew :cardio-core:test           # run tests (requires live PG at localhost:5432/test, user=test, pass=test)
./gradlew compileKotlin               # compile only
```

- **Version** is derived from the latest git tag (`git describe --tags`), fallback to short hash or `"dev"`.
- **JVM 21** is required (toolchain set in all modules).
- Gradle configuration cache is enabled (`gradle.properties`).
- Publishing is via `com.vanniktech.maven.publish` in `cardio-core`, `cardio-protocol`, and `cardio-serialization`.

## Key Patterns

### SQL Syntax
Always use PostgreSQL native positional parameters — `$1`, `$2`, … — in all query strings.

### Params Style
All query/execute methods take `params: List<Any?> = emptyList()` (no `vararg`). Pass a regular list:
```kotlin
db.query("SELECT * FROM users WHERE id = $1", listOf(42)) { row -> ... }
db.execute("DELETE FROM users WHERE id = $1", listOf(42))
```
To pass zero params just omit the argument (default is `emptyList()`):
```kotlin
db.query("SELECT version()") { row -> row.get<String>(0) }
```
Array parameters are passed as a nested list (outer list = params, inner list = array elements):
```kotlin
db.query("SELECT id FROM users WHERE id = ANY($1)", listOf(listOf(1, 2, 3))) { row -> row.get<Int>("id") }
db.execute("DELETE FROM sessions WHERE id = ANY($1)", listOf(intArrayOf(5, 6, 7)))
```

### Row Access
`cardio-core`: `row.get<Int>("id")` (non-null), `row.getOrNull<Int>("id")` (nullable).

Column lookup is **case-insensitive** in `cardio-core` (`Row.indexByName` lowercases keys). Index-based access is also supported: `row.get<Int>(0)` / `row.getOrNull<Int>(0)`.

`DatabaseOperations` exposes `query` (returns `List<T>`), `queryOne` (returns `T?`), `execute` (returns `Long`), `executeReturning` (returns `List<T>`), and `queryFlow` (returns `Flow<T>`). All are available on `Cardio`, `CardioTransaction`, and `CardioRepository`.

`queryOne` is optimized at the wire level: it sends `Execute(maxRows = 1)` so the server streams only one row regardless of result-set size.

### Factory Methods
```kotlin
// suspending — call from a coroutine or runBlocking:
val db = Cardio.new { host = "localhost"; database = "mydb"; username = "u"; password = "p" }
val db = Cardio.newCustom<MyDb> { ... }   // reflection-based subclass
```

Other notable `Cardio.Configuration` fields: `maxSize` (default 10), `minSize` (default 2), `acquireTimeout` (default 30s), `idleTimeout` (default 600s), `applicationName`, `ssl` (`Connection.SslMode.DISABLE` | `PREFER` | `REQUIRE` | `VERIFY_CA` | `VERIFY_FULL`), `sslRootCert: ByteArray?` (PEM-encoded CA certificate for `VERIFY_CA`/`VERIFY_FULL`).

### Transactions
`inTransaction` takes an extension receiver — the `CardioTransaction` is available as `this`:
```kotlin
// cardio-core — extension receiver, no tx parameter:
db.inTransaction {
    execute("INSERT INTO orders ...", listOf(...))
    execute("UPDATE inventory ...", listOf(...))
}

// Return a value from a transaction:
val id: Long = db.inTransaction {
    executeReturning("INSERT INTO orders ... RETURNING id", listOf(...)) { row ->
        row.get<Long>("id")
    }.first()
}
```

Transactions are propagated implicitly through `CoroutineContext`. Calling `db.query(...)` / `db.execute(...)` inside an `inTransaction` block automatically joins the active transaction — no need to pass `tx` manually. Nested `inTransaction` calls join the existing transaction (no real nesting):
```kotlin
db.inTransaction {
    // These all run inside the same transaction:
    db.execute("INSERT ...", listOf(...))   // auto-detected tx
    execute("UPDATE ...", listOf(...))      // via receiver
    db.inTransaction { execute("DELETE ...", listOf(...)) }  // joins existing tx
}
```

`CardioTransaction` also exposes `commit()` and `rollback()` for explicit control; `inTransaction` rolls back automatically on exception.

### Execute with RETURNING
Use `executeReturning` for DML statements with a `RETURNING` clause:
```kotlin
val users = db.executeReturning(
    "INSERT INTO users (name) VALUES ($1) RETURNING id, name",
    listOf("Alice")
) { row ->
    User(row.get("id"), row.get("name"))
}
```

### Streaming (queryFlow)
`queryFlow` returns a cold `Flow<T>` backed by a wire-level cursor (`Execute(maxRows = chunkSize)`). Use it for large result sets to avoid loading all rows into memory:
```kotlin
db.queryFlow("SELECT * FROM large_table", chunkSize = 500) { row ->
    row.get<String>("name")
}.collect { name -> println(name) }
```
The connection is held for the entire duration of collection. The flow is cold and can be collected only once per invocation.

### Repository Pattern
Extend `CardioRepository` to encapsulate data access. The repository delegates directly to `db.query` / `db.execute` and exposes `inTransaction`:
```kotlin
class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {
    suspend fun findById(id: Int) = queryOne("SELECT * FROM users WHERE id = $1", listOf(id)) { row ->
        User(row.get("id"), row.get("name"))
    }

    suspend fun createUser(name: String): User = inTransaction {
        executeReturning("INSERT INTO users (name) VALUES ($1) RETURNING *", listOf(name)) { row ->
            User(row.get("id"), row.get("name"))
        }.first()
    }
}
```

### Custom Type Codecs (`cardio-protocol`)
Implement `TypeCodec<T>` (oid, encode, decode) and register it:
```kotlin
Cardio.new {
    codecs { register(MyEnumCodec) }
    // ...
}
```
Built-in codecs in `TypeCodecRegistry.Default`: Int2/4/8, Float4/8, Text, Bool, ByteArray, UUID (Java + Kotlin `uuid.Uuid`), Instant, LocalDate, JSONB.

### Array Parameters (`cardio-protocol`)
Pass a `List<T>` or a Kotlin primitive array as the array element directly inside the outer params list:
```kotlin
db.query("SELECT id FROM users WHERE id = ANY($1)", listOf(listOf(1, 2, 3))) { row -> row.get<Int>("id") }
db.execute("DELETE FROM sessions WHERE id = ANY($1)", listOf(intArrayOf(5, 6, 7)))
```
Array columns are decoded back to `List<T>`:
```kotlin
val tags: List<String> = row.get("tags")
```
For non-standard element types supply an explicit `ArrayCodec`:
```kotlin
db.query("SELECT * FROM unnest($1)", listOf(Param(myEnumList, ArrayCodec(PgOid.TEXT_ARRAY, MyEnumCodec)))) { ... }
```
Built-in array codecs: `Int2ArrayCodec`, `Int4ArrayCodec`, `Int8ArrayCodec`, `Float4ArrayCodec`, `Float8ArrayCodec`, `TextArrayCodec`, `BoolArrayCodec`, `TimestamptzArrayCodec`, `KotlinUuidArrayCodec`.

### Exception Hierarchy
All Cardio exceptions extend `CardioException`:

| Exception | Extends | When thrown |
|-----------|---------|-------------|
| `CardioException` | `Exception` | Abstract base — never thrown directly |
| `PgException` | `CardioException` | Server returned an `ErrorResponse` (SQL errors, constraint violations, …) |
| `PgConnectException` | `CardioException` | TCP connection failed or startup handshake timed out |
| `PgSslException` | `PgConnectException` | TLS negotiation failed (server declined, bad cert, hostname mismatch) |
| `PgPoolTimeoutException` | `CardioException` | No connection available within `acquireTimeout` |
| `PgConnectionCreationException` | `CardioException` | Pool exhausted all reconnect attempts |

`PgSslException` extends `PgConnectException` because SSL failure is a connection failure — a single `catch (e: PgConnectException)` handles both cases.

### Serialization (`cardio-serialization`)
```kotlin
val user = CardioSerializationFormat.decodeFromRow<User>(row)
```
`CardioDecoder` maps `@Serializable` field names to column names; enum values are matched case-insensitively.

`Extensions.kt` provides mapper-free overloads on `Cardio`, `CardioTransaction`, and `CardioRepository`:
```kotlin
val user: User? = db.queryOne<User>("SELECT id, name FROM users WHERE id = $1", listOf(42))
val users: List<User> = db.query<User>("SELECT * FROM users")
val created: List<User> = db.executeReturning<User>("INSERT INTO users ... RETURNING *", listOf(...))
```

### SSL/TLS (`cardio-protocol`)
All five PostgreSQL SSL modes are supported:

| Mode | Behaviour |
|------|-----------|
| `DISABLE` | Never use TLS. |
| `PREFER` | Request TLS; fall back to plain-text if server declines. |
| `REQUIRE` | Require TLS; reject the connection if server declines. No certificate validation. |
| `VERIFY_CA` | Require TLS + validate server certificate against `sslRootCert`. |
| `VERIFY_FULL` | Require TLS + validate certificate chain AND verify hostname/IP against SANs. |

Configure via `Cardio.Configuration`:
```kotlin
val db = Cardio.new {
    host = "db.example.com"
    ssl = Connection.SslMode.VERIFY_FULL
    sslRootCert = File("ca.pem").readBytes()   // PEM-encoded CA cert
}
```

Or via connection URL (`sslmode` / `sslrootcert` / `sslrootcertpath` query parameters — case-insensitive):
```
postgres://user:pass@host/db?sslmode=verify-full&sslrootcertpath=/etc/ssl/ca.pem
```

`PgSslException` (extends `PgConnectException`) is thrown when TLS negotiation fails. Catching `PgConnectException` covers both plain-TCP and SSL failures.

#### SSL implementation details
- **Handshake**: SSLRequest message (`[0x00,0x00,0x00,0x08, 0x04,0xD2,0x16,0x2F]`) is sent before `StartupMessage`; server replies `'S'` (accepted) or `'N'` (declined).
- **TLS upgrade**: `socket.tls(coroutineContext) { ... }` from `ktor-network-tls`; a new `ByteReadChannel`/`ByteWriteChannel` pair is opened on the upgraded socket.
- **SNI**: Set to the server hostname; **not** set for IP address literals (RFC 6066 §3).
- **Trust managers**: `REQUIRE`/`PREFER` use a stateless `TRUST_ALL_MANAGER` singleton. `VERIFY_CA` chains against the supplied CA. `VERIFY_FULL` additionally verifies SANs (dNSName + iPAddress) with wildcard and RFC 2818 hostname matching; `extractCN()` uses `LdapName` for RFC 2253 DN parsing.
- **Thread safety**: `SECURE_RANDOM`, `IPV4_REGEX`, `SSL_REQUEST_BYTES`, and `TRUST_ALL_MANAGER` are companion `val`s — allocated once, shared safely across all connections.
- **`TimeoutCancellationException`**: All three `withTimeout` blocks in `connect()` catch `TimeoutCancellationException` **before** `CancellationException` to avoid propagating a connection timeout as a coroutine scope cancellation.

## Key Files
- `cardio-protocol/…/connection/Connection.kt` — full PG wire protocol (auth, SCRAM-SHA-256, extended query, SSL/TLS, cursor/flow, queryOne optimization)
- `cardio-protocol/…/connection/PgSslException.kt` — SSL-specific exception (extends `PgConnectException`)
- `cardio-protocol/…/connection/ConnectionPool.kt` — coroutine-based pool (Semaphore + Channel; `borrowConnection`/`returnConnection` for Flow support)
- `cardio-protocol/…/CardioException.kt` — sealed base exception for the entire Cardio hierarchy
- `cardio-protocol/…/codec/BuiltinCodecs.kt` — all binary codecs
- `cardio-protocol/…/codec/Param.kt` — `Param` wrapper for explicit codec overrides on query parameters
- `cardio-core/…/Cardio.kt` — public API entry point (`inTransaction` extension receiver, CoroutineContext auto-routing, `sslMode`, `sslRootCert` in `Configuration`)
- `cardio-core/…/CardioTransaction.kt` — transaction handle + `Context` for CoroutineContext propagation
- `cardio-core/…/UrlParser.kt` — URL parser (`sslmode`, `sslrootcert`, `sslrootcertpath` params)
- `cardio-core/…/SslTests.kt` — 31 SSL/TLS tests
- `gradle/libs.versions.toml` — all version pins (includes `kotlinx-coroutines-test` for tests)
