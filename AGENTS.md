# Cardio – AI Agent Guide

## Project Overview
Cardio is a Kotlin library for non-blocking PostgreSQL access using coroutines. It contains **two parallel implementations** and an optional serialization add-on.

## Module Map

```
cardio-protocol      – Custom PostgreSQL wire protocol over Ktor sockets (no Vert.x)
cardio-core          – User-facing API on top of cardio-protocol (Cardio, CardioRepository, CardioTransaction)
cardio-serialization – Optional kotlinx.serialization bridge (depends on cardio-core)
cardio-postgres      – Legacy/alternative implementation backed by Vert.x PG Client 5.x
```

**Dependency graph:** `cardio-serialization` → `cardio-core` → `cardio-protocol`  
`cardio-postgres` is standalone (depends on `cardio-protocol` only for shared types).

> ⚠️ `cardio-postgres` is **deprecated** and will be removed in a future release. All new development happens in `cardio-core` + `cardio-protocol`. `cardio-core`, `cardio-protocol`, and `cardio-serialization` are published to Maven Central; `cardio-postgres` is no longer published.

## Build & Test

```bash
./gradlew build                       # build all modules
./gradlew :cardio-core:test           # run tests (requires live PG at localhost:5432/test, user=test, pass=test)
./gradlew compileKotlin               # compile only
```

- **Version** is derived from the latest git tag (`git describe --tags`), fallback to short hash or `"dev"`.
- **JVM 21** is required (toolchain set in all modules).
- Gradle configuration cache is enabled (`gradle.properties`).
- Publishing is via `com.vanniktech.maven.publish` in `cardio-core`, `cardio-protocol`, and `cardio-serialization`; `cardio-postgres` has no publish configuration.

## Key Patterns

### SQL Syntax
Always use PostgreSQL native positional parameters — `$1`, `$2`, … — in all query strings.

### Row Access
| Module | Non-null | Nullable |
|---|---|---|
| `cardio-core` | `row.get<Int>("id")` | `row.getOrNull<Int>("id")` |
| `cardio-postgres` | `row.getAs<Int>("id")` | `row.getAsNullable<Int>("id")` |

Column lookup is **case-insensitive** in `cardio-core` (`Row.indexByName` lowercases keys). Index-based access is also supported: `row.get<Int>(0)` / `row.getOrNull<Int>(0)`.

`DatabaseOperations` exposes both `query` (returns `List<T>`) and `queryOne` (returns `T?`); both are available on `Cardio`, `CardioTransaction`, and `CardioRepository`.

### Factory Methods
```kotlin
// cardio-core (suspending — call from a coroutine or runBlocking):
val db = Cardio.new { host = "localhost"; database = "mydb"; username = "u"; password = "p" }
val db = Cardio.newCustom<MyDb> { ... }   // reflection-based subclass

// cardio-postgres (suspending):
val db = Cardio.create<MyDb> { connectOptions = PgConnectOptions().apply { ... } }
val db = Cardio.create<MyDb> { url("postgres://u:p@localhost:5432/mydb") }
```

Other notable `Cardio.Configuration` fields: `maxSize` (default 10), `minSize` (default 2), `acquireTimeout` (default 30s), `idleTimeout` (default 600s), `applicationName`, `ssl` (`Connection.SslMode.DISABLE` | `PREFER` | `REQUIRE` | `VERIFY_CA` | `VERIFY_FULL`), `sslRootCert: ByteArray?` (PEM-encoded CA certificate for `VERIFY_CA`/`VERIFY_FULL`).

### Transactions
```kotlin
// cardio-core — explicit tx parameter:
db.inTransaction { tx -> tx.query("SELECT ...") { row -> ... } }

// cardio-postgres — tx propagated via CoroutineContext:
db.inTransaction { query("SELECT ...") { row -> ... } }
```
`CardioTransaction` also exposes `tx.commit()` and `tx.rollback()` for explicit control; `inTransaction` rolls back automatically on exception.

### Repository Pattern
Extend `CardioRepository` (either module) to encapsulate data access. The repository delegates directly to `db.query` / `db.execute` and exposes `inTransaction`.

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
Pass a `List<T>` or a Kotlin primitive array directly as a query parameter; Cardio encodes it as the PostgreSQL binary array format automatically:
```kotlin
db.query("SELECT id FROM users WHERE id = ANY($1)", listOf(1, 2, 3)) { row -> row.get<Int>("id") }
db.execute("DELETE FROM sessions WHERE id = ANY($1)", intArrayOf(5, 6, 7))
```
Array columns are decoded back to `List<T>`:
```kotlin
val tags: List<String> = row.get("tags")
```
For non-standard element types supply an explicit `ArrayCodec`:
```kotlin
db.query("SELECT * FROM unnest($1)", Param(myEnumList, ArrayCodec(PgOid.TEXT_ARRAY, MyEnumCodec))) { ... }
```
Built-in array codecs: `Int2ArrayCodec`, `Int4ArrayCodec`, `Int8ArrayCodec`, `Float4ArrayCodec`, `Float8ArrayCodec`, `TextArrayCodec`, `BoolArrayCodec`, `TimestamptzArrayCodec`, `KotlinUuidArrayCodec`.

### Serialization (`cardio-serialization`)
```kotlin
val user = CardioSerializationFormat.decodeFromRow<User>(row)
```
`CardioDecoder` maps `@Serializable` field names to column names; enum values are matched case-insensitively.

`Extensions.kt` provides mapper-free overloads on `Cardio`, `CardioTransaction`, and `CardioRepository`:
```kotlin
val user: User? = db.queryOne<User>("SELECT id, name FROM users WHERE id = $1", 42)
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
    sslMode = Connection.SslMode.VERIFY_FULL
    sslRootCert = File("ca.pem").readBytes()   // PEM-encoded CA cert
}
```

Or via connection URL (`sslmode` / `sslrootcert` / `sslrootcertpath` query parameters):
```
postgres://user:pass@host/db?sslmode=verify-full&sslrootcertpath=/etc/ssl/ca.pem
```

`PgSslException` (extends `Exception`) is thrown when TLS negotiation fails (server declined a required mode, certificate validation failed, or hostname mismatch).

#### SSL implementation details
- **Handshake**: SSLRequest message (`[0x00,0x00,0x00,0x08, 0x04,0xD2,0x16,0x2F]`) is sent before `StartupMessage`; server replies `'S'` (accepted) or `'N'` (declined).
- **TLS upgrade**: `socket.tls(coroutineContext) { ... }` from `ktor-network-tls`; a new `ByteReadChannel`/`ByteWriteChannel` pair is opened on the upgraded socket.
- **SNI**: Set to the server hostname; **not** set for IP address literals (RFC 6066 §3).
- **Trust managers**: `REQUIRE`/`PREFER` use a stateless `TRUST_ALL_MANAGER` singleton. `VERIFY_CA` chains against the supplied CA. `VERIFY_FULL` additionally verifies SANs (dNSName + iPAddress) with wildcard and RFC 2818 hostname matching; `extractCN()` uses `LdapName` for RFC 2253 DN parsing.
- **Thread safety**: `SECURE_RANDOM`, `IPV4_REGEX`, `SSL_REQUEST_BYTES`, and `TRUST_ALL_MANAGER` are companion `val`s — allocated once, shared safely across all connections.
- **`TimeoutCancellationException`**: All three `withTimeout` blocks in `connect()` catch `TimeoutCancellationException` **before** `CancellationException` to avoid propagating a connection timeout as a coroutine scope cancellation.

## Key Files
- `cardio-protocol/…/connection/Connection.kt` — full PG wire protocol (auth, SCRAM-SHA-256, extended query, SSL/TLS)
- `cardio-protocol/…/connection/PgSslException.kt` — SSL-specific exception
- `cardio-protocol/…/connection/ConnectionPool.kt` — coroutine-based pool (Semaphore + Channel)
- `cardio-protocol/…/codec/BuiltinCodecs.kt` — all binary codecs
- `cardio-protocol/…/codec/Param.kt` — `Param` wrapper for explicit codec overrides on query parameters
- `cardio-core/…/Cardio.kt` — public API entry point (`sslMode`, `sslRootCert` in `Configuration`)
- `cardio-core/…/UrlParser.kt` — URL parser (`sslmode`, `sslrootcert`, `sslrootcertpath` params)
- `cardio-core/…/SslTests.kt` — 31 SSL/TLS tests
- `cardio-postgres/…/ConnectionUrl.kt` — URL parser for `url("postgres://…")`
- `gradle/libs.versions.toml` — all version pins

