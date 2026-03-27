# Cardio [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Blad3Mak3r/cardio)

Cardio is a lightweight Kotlin library for non-blocking PostgreSQL access using pure coroutines. Unlike solutions that wrap Vert.x or Reactor, Cardio implements the **PostgreSQL wire protocol directly** over Ktor network sockets — no heavy runtimes, no reactive streams, just idiomatic Kotlin.

> ⚠️ **`cardio-postgres` is deprecated and will be removed in a future release.**
> It was the original Vert.x PG Client-backed implementation and is the currently published Maven artifact.
> All new development happens in `cardio-core` + `cardio-protocol`. Migrate as soon as possible.

---

## Features

- 🔌 **Custom PostgreSQL wire protocol** — implements PG protocol 3.0 directly over Ktor TCP sockets, no Vert.x, no JDBC, no R2DBC or Reactor required.
- ⚡ **Pure coroutine-native** — every operation is a suspending function; no `Future`, no `Mono`, no callback adapters.
- 🔒 **SCRAM-SHA-256 & MD5 authentication** — modern secure auth out of the box.
- 📦 **Binary wire encoding** — all supported types are sent and received in binary format, not as text strings.
- 🔁 **Coroutine-based connection pool** — built on `Semaphore` + `Channel` from `kotlinx.coroutines`, no external pool library.
- 🧩 **Pluggable type codecs** — implement `TypeCodec<T>` to teach Cardio any custom or user-defined PostgreSQL type.
- 🗃️ **Built-in codecs** — `Int2/4/8`, `Float4/8`, `Text`, `Bool`, `ByteArray`, `UUID`, `Instant`, `LocalDate`, `JSONB` out of the box.
- 📊 **Pool observability** — `db.stats` exposes live counters (active/idle connections, total acquired, errors).
- 🧾 **kotlinx.serialization bridge** — deserialize a `Row` into a `@Serializable` data class in one line via `cardio-serialization`.
- 🏛️ **Repository pattern** — extend `CardioRepository` to encapsulate all data-access logic cleanly.
- 🔀 **Transaction support** — first-class `inTransaction` with automatic rollback on failure.
- 🪶 **Minimal footprint** — runtime dependencies are only `ktor-network` and `kotlinx-coroutines-core`.
- 🔑 **SSL support** — configurable SSL mode (`DISABLE`, `PREFER`, `REQUIRE`).
- 📝 **Raw SQL** — plain PostgreSQL with native positional parameters (`$1`, `$2`, …); no ORM magic, no DSL.

---

## How it works

Cardio is built in layers:

```
cardio-serialization  ← optional kotlinx.serialization bridge
       ↓
 cardio-core          ← public API (Cardio, CardioRepository, CardioTransaction)
       ↓
cardio-protocol       ← PostgreSQL wire protocol 3.0 over Ktor TCP sockets
```

1. **`cardio-protocol`** opens a raw TCP socket using `ktor-network`, performs authentication (SCRAM-SHA-256 or MD5) and speaks the PostgreSQL extended query protocol (Parse → Bind → Describe → Execute → Sync). All values are transferred in **binary format**, decoded via pluggable `TypeCodec<T>` implementations. The `ConnectionPool` is built entirely on `kotlinx.coroutines` primitives (`Semaphore` + `Channel`).

2. **`cardio-core`** wraps the pool in a clean public API: `Cardio`, `CardioTransaction`, and `CardioRepository`. No reflection except for the optional `Cardio.newCustom<T>` factory.

3. **`cardio-serialization`** provides a `CardioDecoder` that bridges `@Serializable` data classes to `Row`, so you can deserialize query results without writing manual mapping code.

---

## Why Cardio?

| | Cardio | Vert.x PG Client | R2DBC | Exposed / JOOQ |
|---|---|---|---|---|
| Runtime dependency | Ktor Network + coroutines | Vert.x Core + Netty | Reactor Core + R2DBC SPI | JDBC / Spring |
| Async model | Native coroutines | Vert.x Future → coroutines | Reactive Streams (Flux/Mono) | Blocking or coroutines (Exposed) |
| Wire protocol | Custom (binary) | Native (binary) | Depends on driver | JDBC |
| ORM / DSL | ❌ Raw SQL | ❌ Raw SQL | ❌ Raw SQL | ✅ DSL |
| kotlinx.serialization | ✅ | ❌ | ❌ | ❌ |
| Dependency footprint | **Minimal** | Medium | High | High |

**Key strengths:**
- **No Vert.x, no Reactor** — the only runtime dependencies are `ktor-network` and `kotlinx-coroutines-core`.
- **Truly coroutine-native** — suspending functions everywhere, no `Future`, no `Mono`, no callback adapters.
- **Binary wire protocol** — all supported types (Int, Long, Float, Double, Boolean, UUID, Instant, LocalDate, JSONB, …) are encoded and decoded in binary, not as text strings.
- **SCRAM-SHA-256** — modern, secure authentication out of the box; MD5 also supported.
- **Pluggable codecs** — implement `TypeCodec<T>` to teach Cardio any custom PostgreSQL type.
- **kotlinx.serialization** — map a `Row` to a `@Serializable` class in one line.
- **Pool observability** — `db.stats` exposes live counters (active/idle connections, total acquired, errors, …).
- **Zero ORM magic** — you write plain PostgreSQL SQL with native positional parameters (`$1`, `$2`, …).

---

## Installation

> **Note:** `cardio-core` is not yet published to Maven Central. Until it is, use the source directly or publish it to your local Maven repository with `./gradlew :cardio-core:publishToMavenLocal`.

---

## Usage

### Create a connection pool

```kotlin
import io.github.blad3mak3r.cardio.core.Cardio

val db = Cardio.new {
    host     = "localhost"
    port     = 5432
    database = "mydb"
    username = "user"
    password = "secret"
    maxSize  = 10
}
```

For a typed subclass (e.g. to inject into repositories):

```kotlin
class MyDb(pool: ConnectionPool) : Cardio(pool)

val db = Cardio.newCustom<MyDb> {
    host = "localhost"; database = "mydb"; username = "user"; password = "secret"
}
```

### Queries

```kotlin
val users = db.query("SELECT id, name FROM users WHERE active = $1", true) { row ->
    User(
        id   = row.get<Int>("id"),
        name = row.get<String>("name")
    )
}
```

Use `row.getOrNull<T>()` for nullable columns. Column names are **case-insensitive**.

### Transactions

```kotlin
db.inTransaction { tx ->
    val id = tx.query("INSERT INTO users (name) RETURNING id", "Alice") { row ->
        row.get<Int>("id")
    }.first()

    tx.execute("INSERT INTO audit (user_id, event) VALUES ($1, $2)", id, "created")
}
```

### Repositories

```kotlin
class UserRepository(db: Cardio) : CardioRepository(db) {

    suspend fun findById(id: Int): User? =
        queryOne("SELECT id, name FROM users WHERE id = $1", id) { row ->
            User(id = row.get("id"), name = row.get("name"))
        }

    suspend fun create(name: String): Long =
        execute("INSERT INTO users (name) VALUES ($1)", name)
}
```

### kotlinx.serialization

```kotlin
@Serializable
data class User(val id: Int, val name: String)

val user = db.queryOne("SELECT id, name FROM users WHERE id = $1", 42) { row ->
    CardioSerializationFormat.decodeFromRow<User>(row)
}
```

### Custom codecs

Implement `TypeCodec<T>` for any PostgreSQL type and register it at startup:

```kotlin
val db = Cardio.new {
    host = "localhost"; database = "mydb"; username = "user"; password = "secret"
    codecs {
        register(MyEnumCodec)  // implements TypeCodec<MyEnum>
    }
}
```

Built-in codecs: `Int2`, `Int4`, `Int8`, `Float4`, `Float8`, `Text`, `Bool`, `ByteArray`, `UUID` (Java + Kotlin `uuid.Uuid`), `Instant`, `LocalDate`, `JSONB`.

### Pool statistics

```kotlin
val stats = db.stats
println("Active: ${stats.activeConnections} / ${stats.totalConnections}")
println("Total acquired: ${stats.totalAcquired}, errors: ${stats.totalErrors}")
```

---

## SSL

```kotlin
val db = Cardio.new {
    // ...
    ssl = Connection.SslMode.REQUIRE  // DISABLE | PREFER | REQUIRE
}
```

---

## Building

```bash
./gradlew build                 # build all modules
./gradlew :cardio-core:test     # integration tests (requires PostgreSQL at localhost:5432/test, user=test, pass=test)
./gradlew compileKotlin         # compile only
```

Requires **JVM 21**. Version is derived from the latest git tag.
