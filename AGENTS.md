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

> Prefer `cardio-core` for new features; `cardio-postgres` is the published artifact and uses Vert.x.

## Build & Test

```bash
./gradlew build                       # build all modules
./gradlew :cardio-core:test           # run tests (requires live PG at localhost:5432/test, user=test, pass=test)
./gradlew compileKotlin               # compile only
```

- **Version** is derived from the latest git tag (`git describe --tags`), fallback to short hash or `"dev"`.
- **JVM 21** is required (toolchain set in `cardio-core` and `cardio-postgres`).
- Gradle configuration cache is enabled (`gradle.properties`).
- Publishing is via `com.vanniktech.maven.publish` plugin in `cardio-postgres/build.gradle.kts` only.

## Key Patterns

### SQL Syntax
Always use PostgreSQL native positional parameters — `$1`, `$2`, … — in all query strings.

### Row Access
| Module | Non-null | Nullable |
|---|---|---|
| `cardio-core` | `row.get<Int>("id")` | `row.getOrNull<Int>("id")` |
| `cardio-postgres` | `row.getAs<Int>("id")` | `row.getAsNullable<Int>("id")` |

Column lookup is **case-insensitive** in `cardio-core` (`Row.indexByName` lowercases keys).

### Factory Methods
```kotlin
// cardio-core (synchronous construction):
val db = Cardio.new { host = "localhost"; database = "mydb"; username = "u"; password = "p" }
val db = Cardio.newCustom<MyDb> { ... }   // reflection-based subclass

// cardio-postgres (suspending):
val db = Cardio.create<MyDb> { connectOptions = PgConnectOptions().apply { ... } }
val db = Cardio.create<MyDb> { url("postgres://u:p@localhost:5432/mydb") }
```

### Transactions
```kotlin
// cardio-core — explicit tx parameter:
db.inTransaction { tx -> tx.query("SELECT ...") { row -> ... } }

// cardio-postgres — tx propagated via CoroutineContext:
db.inTransaction { query("SELECT ...") { row -> ... } }
```

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

### Serialization (`cardio-serialization`)
```kotlin
val user = CardioSerializationFormat.decodeFromRow<User>(row)
```
`CardioDecoder` maps `@Serializable` field names to column names; enum values are matched case-insensitively.

## Key Files
- `cardio-protocol/…/connection/Connection.kt` — full PG wire protocol (auth, SCRAM-SHA-256, extended query)
- `cardio-protocol/…/connection/ConnectionPool.kt` — coroutine-based pool (Semaphore + Channel)
- `cardio-protocol/…/codec/BuiltinCodecs.kt` — all binary codecs
- `cardio-core/…/Cardio.kt` — public API entry point
- `cardio-postgres/…/ConnectionUrl.kt` — URL parser for `url("postgres://…")`
- `gradle/libs.versions.toml` — all version pins

