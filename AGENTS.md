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

Other notable `Cardio.Configuration` fields: `maxSize` (default 10), `minSize` (default 2), `acquireTimeout` (default 30s), `idleTimeout` (default 600s), `applicationName`, `ssl` (`Connection.SslMode.DISABLE` | `PREFER` | `REQUIRE`).

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

## Key Files
- `cardio-protocol/…/connection/Connection.kt` — full PG wire protocol (auth, SCRAM-SHA-256, extended query)
- `cardio-protocol/…/connection/ConnectionPool.kt` — coroutine-based pool (Semaphore + Channel)
- `cardio-protocol/…/codec/BuiltinCodecs.kt` — all binary codecs
- `cardio-protocol/…/codec/Param.kt` — `Param` wrapper for explicit codec overrides on query parameters
- `cardio-core/…/Cardio.kt` — public API entry point
- `cardio-postgres/…/ConnectionUrl.kt` — URL parser for `url("postgres://…")`
- `gradle/libs.versions.toml` — all version pins

