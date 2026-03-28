# Cardio [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Blad3Mak3r/cardio)

Cardio is a lightweight Kotlin library for non-blocking PostgreSQL access using pure coroutines. Unlike solutions that wrap Vert.x or Reactor, Cardio implements the **PostgreSQL wire protocol directly** over Ktor network sockets — no heavy runtimes, no reactive streams, just idiomatic Kotlin.

> ⚠️ **`cardio-postgres` is deprecated and will be removed in a future release.**
> It was the original Vert.x PG Client-backed implementation and is the currently published Maven artifact.
> All new development happens in `cardio-core` + `cardio-protocol`. Migrate as soon as possible.

---

## 📑 Table of Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Why Cardio?](#why-cardio)
- [Installation](#installation)
  - [Gradle (Kotlin DSL)](#gradle-kotlin-dsl)
  - [Gradle (Groovy DSL)](#gradle-groovy-dsl)
  - [Maven](#maven)
- [Usage](#usage)
  - [Create a connection pool](#create-a-connection-pool)
  - [Queries](#queries)
  - [Transactions](#transactions)
  - [Repositories](#repositories)
  - [kotlinx.serialization](#kotlinxserialization)
  - [Arrays](#arrays)
  - [Supported types](#supported-types)
  - [Custom codecs](#custom-codecs)
  - [Pool statistics](#pool-statistics)
- [SSL](#ssl)
- [Building](#building)

---

## Features

- 🔌 **Custom PostgreSQL wire protocol** — implements PG protocol 3.0 directly over Ktor TCP sockets, no Vert.x, no JDBC, no R2DBC or Reactor required.
- ⚡ **Pure coroutine-native** — every operation is a suspending function; no `Future`, no `Mono`, no callback adapters.
- 🔒 **SCRAM-SHA-256 & MD5 authentication** — modern secure auth out of the box.
- 📦 **Binary wire encoding** — all supported types are sent and received in binary format, not as text strings.
- 🔁 **Coroutine-based connection pool** — built on `Semaphore` + `Channel` from `kotlinx.coroutines`, no external pool library.
- 🧩 **Pluggable type codecs** — implement `TypeCodec<T>` to teach Cardio any custom or user-defined PostgreSQL type.
- 🗃️ **Built-in codecs** — `Int2/4/8`, `Float4/8`, `Numeric`, `Text`, `Bool`, `ByteArray`, `UUID`, `Instant`, `Timestamp`, `LocalDate`, `Interval`, `JSONB` out of the box.
- 📋 **Array parameters & results** — pass any `List<T>` or Kotlin primitive array (`IntArray`, `LongArray`, …) directly as a query parameter; array columns are decoded back to `List<T>` automatically.
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
- **Binary wire protocol** — all supported types (Int, Long, Float, Double, Boolean, UUID, Instant, LocalDate, JSONB, arrays, …) are encoded and decoded in binary, not as text strings.
- **SCRAM-SHA-256** — modern, secure authentication out of the box; MD5 also supported.
- **Pluggable codecs** — implement `TypeCodec<T>` to teach Cardio any custom PostgreSQL type.
- **kotlinx.serialization** — map a `Row` to a `@Serializable` class in one line.
- **Pool observability** — `db.stats` exposes live counters (active/idle connections, total acquired, errors, …).
- **Zero ORM magic** — you write plain PostgreSQL SQL with native positional parameters (`$1`, `$2`, …).

---

## Installation

> Starting with **1.0.0-alpha.1**, `cardio-core`, `cardio-serialization` and `cardio-protocol` are published to Maven Central.
> `cardio-protocol` is a transitive dependency of `cardio-core` — you only need to declare it explicitly if you use the protocol layer directly.

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    // Core API — pulls in cardio-protocol automatically
    implementation("io.github.blad3mak3r:cardio-core:1.0.0-alpha.1")

    // Optional: kotlinx.serialization bridge
    implementation("io.github.blad3mak3r:cardio-serialization:1.0.0-alpha.1")

    // Only needed when using the protocol layer directly (e.g. custom codecs, ConnectionPool)
    implementation("io.github.blad3mak3r:cardio-protocol:1.0.0-alpha.1")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.blad3mak3r:cardio-core:1.0.0-alpha.1'
    implementation 'io.github.blad3mak3r:cardio-serialization:1.0.0-alpha.1' // optional
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.blad3mak3r</groupId>
    <artifactId>cardio-core</artifactId>
    <version>1.0.0-alpha.1</version>
</dependency>

<!-- Optional: kotlinx.serialization bridge -->
<dependency>
    <groupId>io.github.blad3mak3r</groupId>
    <artifactId>cardio-serialization</artifactId>
    <version>1.0.0-alpha.1</version>
</dependency>
```

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

Alternatively, use a connection URL with the `url()` helper:

```kotlin
val db = Cardio.new {
    url("postgres://user:secret@localhost:5432/mydb")
}
```

The URL format is `postgres[ql]://username:password@host:port/database`. You can also pass optional query parameters:

| Parameter | Values                                  | Default    |
|---|-----------------------------------------|------------|
| `sslMode` | `disable` `prefer` `require` |  `disable` |
| `applicationName` | any string                              | —          |

```kotlin
val db = Cardio.new {
    url("postgres://user:secret@localhost:5432/mydb?sslMode=require&applicationName=my-app")
    maxSize = 20
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

### Arrays

Cardio supports PostgreSQL array parameters natively, enabling SQL functions like `ANY($1)`, `unnest($1)`, `= ALL($1)`, etc.

Pass a Kotlin `List<T>` or a primitive array and Cardio will encode it in the PostgreSQL binary array format automatically:

```kotlin
// ANY($1) — filter by a set of ids
val users = db.query(
    "SELECT id, name FROM users WHERE id = ANY($1)",
    listOf(1, 2, 3)
) { row -> User(row.get("id"), row.get("name")) }

// unnest($1) — expand an array into rows
val ids = db.query(
    "SELECT * FROM unnest($1) AS id",
    listOf(10L, 20L, 30L)
) { row -> row.get<Long>("id") }

// Kotlin primitive arrays work too
db.execute("DELETE FROM sessions WHERE id = ANY($1)", intArrayOf(5, 6, 7))

// Mixed queries — scalar and array params together
db.query(
    "SELECT * FROM events WHERE tenant_id = $1 AND status = ANY($2)",
    tenantId, listOf("active", "pending")
) { row -> /* … */ }
```

Array results (columns with an array type) are decoded back to `List<T>`:

```kotlin
val row = db.queryOne("SELECT tags FROM posts WHERE id = $1", 42) { it }
val tags: List<String> = row.get("tags")
```

**Supported element types** for automatic codec inference:

| Kotlin type | PostgreSQL array type |
|---|---|
| `List<Int>` / `IntArray` | `int4[]` |
| `List<Short>` / `ShortArray` | `int2[]` |
| `List<Long>` / `LongArray` | `int8[]` |
| `List<Float>` / `FloatArray` | `float4[]` |
| `List<Double>` / `DoubleArray` | `float8[]` |
| `List<java.math.BigDecimal>` | `numeric[]` |
| `List<String>` | `text[]` |
| `List<Boolean>` / `BooleanArray` | `bool[]` |
| `List<kotlin.uuid.Uuid>` | `uuid[]` |
| `List<kotlin.time.Instant>` | `timestamptz[]` |
| `List<kotlinx.datetime.LocalDateTime>` | `timestamp[]` |
| `List<PgInterval>` | `interval[]` |

For any other element type, supply an explicit `ArrayCodec`:

```kotlin
db.query(
    "SELECT * FROM unnest($1) AS s",
    Param(myEnumList, ArrayCodec(PgOid.TEXT_ARRAY, MyEnumCodec))
) { row -> /* … */ }
```

### Supported types

All types are transferred in **binary format** over the wire.

#### Scalar types

| PostgreSQL type | Kotlin / Cardio type |
|---|---|
| [`SMALLINT`](https://www.postgresql.org/docs/current/datatype-numeric.html) | `kotlin.Short` |
| [`INTEGER`](https://www.postgresql.org/docs/current/datatype-numeric.html) | `kotlin.Int` |
| [`BIGINT`](https://www.postgresql.org/docs/current/datatype-numeric.html) | `kotlin.Long` |
| [`REAL`](https://www.postgresql.org/docs/current/datatype-numeric.html) | `kotlin.Float` |
| [`DOUBLE PRECISION`](https://www.postgresql.org/docs/current/datatype-numeric.html) | `kotlin.Double` |
| [`NUMERIC` / `DECIMAL`](https://www.postgresql.org/docs/current/datatype-numeric.html) | `java.math.BigDecimal` |
| [`TEXT`](https://www.postgresql.org/docs/current/datatype-character.html) | `kotlin.String` |
| [`BOOLEAN`](https://www.postgresql.org/docs/current/datatype-boolean.html) | `kotlin.Boolean` |
| [`BYTEA`](https://www.postgresql.org/docs/current/datatype-binary.html) | `kotlin.ByteArray` |
| [`UUID`](https://www.postgresql.org/docs/current/datatype-uuid.html) | `kotlin.uuid.Uuid` |
| [`TIMESTAMP WITH TIME ZONE`](https://www.postgresql.org/docs/current/datatype-datetime.html) | `kotlin.time.Instant` |
| [`TIMESTAMP`](https://www.postgresql.org/docs/current/datatype-datetime.html) | `kotlinx.datetime.LocalDateTime` |
| [`DATE`](https://www.postgresql.org/docs/current/datatype-datetime.html) | `kotlinx.datetime.LocalDate` |
| [`INTERVAL`](https://www.postgresql.org/docs/current/datatype-datetime.html) | `io.github.blad3mak3r.cardio.protocol.PgInterval` |
| [`INT4RANGE`](https://www.postgresql.org/docs/current/rangetypes.html) | `io.github.blad3mak3r.cardio.protocol.PgRange<Int>` |
| [`INT8RANGE`](https://www.postgresql.org/docs/current/rangetypes.html) | `io.github.blad3mak3r.cardio.protocol.PgRange<Long>` |
| [`NUMRANGE`](https://www.postgresql.org/docs/current/rangetypes.html) | `io.github.blad3mak3r.cardio.protocol.PgRange<BigDecimal>` |
| [`TSRANGE`](https://www.postgresql.org/docs/current/rangetypes.html) | `io.github.blad3mak3r.cardio.protocol.PgRange<LocalDateTime>` |
| [`TSTZRANGE`](https://www.postgresql.org/docs/current/rangetypes.html) | `io.github.blad3mak3r.cardio.protocol.PgRange<Instant>` |
| [`DATERANGE`](https://www.postgresql.org/docs/current/rangetypes.html) | `io.github.blad3mak3r.cardio.protocol.PgRange<LocalDate>` |
| [`JSONB`](https://www.postgresql.org/docs/current/datatype-json.html) | `kotlin.String` |

#### Array types

[PostgreSQL array](https://www.postgresql.org/docs/current/arrays.html) columns and parameters are decoded/encoded automatically using the built-in array codecs.

| PostgreSQL type | Kotlin / Cardio type |
|---|---|
| [`SMALLINT[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<Short>` / `ShortArray` |
| [`INTEGER[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<Int>` / `IntArray` |
| [`BIGINT[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<Long>` / `LongArray` |
| [`REAL[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<Float>` / `FloatArray` |
| [`DOUBLE PRECISION[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<Double>` / `DoubleArray` |
| [`NUMERIC[]` / `DECIMAL[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<java.math.BigDecimal>` |
| [`TEXT[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<String>` |
| [`BOOLEAN[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<Boolean>` / `BooleanArray` |
| [`UUID[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<kotlin.uuid.Uuid>` |
| [`TIMESTAMP WITH TIME ZONE[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<kotlin.time.Instant>` |
| [`TIMESTAMP[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<kotlinx.datetime.LocalDateTime>` |
| [`INTERVAL[]`](https://www.postgresql.org/docs/current/arrays.html) | `List<PgInterval>` |

---

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

Built-in scalar codecs: `Int2`, `Int4`, `Int8`, `Float4`, `Float8`, `Numeric`, `Text`, `Varchar` (`CHARACTER VARYING`), `Bpchar` (`CHAR(n)`), `Bool`, `ByteArray`, `UUID` (`kotlin.uuid.Uuid`), `Instant` (`kotlin.time.Instant`), `Timestamp` (`kotlinx.datetime.LocalDateTime`), `LocalDate` (`kotlinx.datetime.LocalDate`), `Interval` (`PgInterval`), `Int4Range` (`PgRange<Int>`), `Int8Range` (`PgRange<Long>`), `NumRange` (`PgRange<BigDecimal>`), `TsRange` (`PgRange<LocalDateTime>`), `TsTzRange` (`PgRange<Instant>`), `DateRange` (`PgRange<LocalDate>`), `JSONB`.

Built-in array codecs (automatically selected when a `List<T>` or primitive array is passed): `Int2Array`, `Int4Array`, `Int8Array`, `Float4Array`, `Float8Array`, `NumericArray`, `TextArray`, `VarcharArray`, `BoolArray`, `UuidArray`, `TimestampArray`, `TimestamptzArray`, `IntervalArray`.

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
