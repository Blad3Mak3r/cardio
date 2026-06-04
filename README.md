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
  - [Execute with RETURNING](#execute-with-returning)
  - [Streaming (queryFlow)](#streaming-queryflow)
  - [Transactions](#transactions)
  - [LISTEN / NOTIFY](#listen--notify)
  - [Repositories](#repositories)
  - [kotlinx.serialization](#kotlinxserialization)
  - [Arrays](#arrays)
  - [Supported types](#supported-types)
  - [Custom codecs](#custom-codecs)
  - [Pool statistics](#pool-statistics)
  - [Exception hierarchy](#exception-hierarchy)
- [SSL / TLS](#ssl--tls)
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
- 🔀 **Transaction support** — first-class `inTransaction` with automatic rollback on failure, implicit propagation via `CoroutineContext`, and safe nested-call join semantics.
- 📡 **Streaming results** — `queryFlow` uses wire-level cursors (`Execute(maxRows = N)` + `PortalSuspended`) to stream large result sets without materialising the whole result in memory.
- 🪶 **Minimal footprint** — runtime dependencies are only `ktor-network` and `kotlinx-coroutines-core`.
- 🔑 **Full TLS/SSL support** — five SSL modes (`DISABLE`, `PREFER`, `REQUIRE`, `VERIFY_CA`, `VERIFY_FULL`) with proper certificate chain and hostname verification (RFC 2818 / RFC 6125).
- 📢 **Native LISTEN / NOTIFY** — `PgListener` opens a dedicated connection outside the pool, delivers notifications as a `SharedFlow<PgNotification>`, and reconnects automatically on failure. `db.notify()` sends notifications injection-safely via `pg_notify`.
- 📝 **Raw SQL** — plain PostgreSQL with native positional parameters (`$1`, `$2`, …); no ORM magic, no DSL.
- 🛡️ **Typed exception hierarchy** — all errors extend `CardioException`; catch the specific subtype you care about (`PgException`, `PgConnectException`, `PgSslException`, `PgPoolTimeoutException`).

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

2. **`cardio-core`** wraps the pool in a clean public API: `Cardio`, `CardioTransaction`, and `CardioRepository`. Transactions are propagated implicitly through the `CoroutineContext` — any `db.query()` or `db.execute()` call made inside an `inTransaction` block automatically joins the active transaction. No reflection except for the optional `Cardio.newCustom<T>` factory.

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

| Parameter | Values | Default |
|---|---|---|
| `sslMode` | `disable` `prefer` `require` `verify-ca` `verify-full` | `disable` |
| `applicationName` | any string | — |

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

Query parameters are passed as a `List<Any?>`. Omit the list entirely when there are no parameters (defaults to `emptyList()`).

```kotlin
// No parameters
val version = db.query("SELECT version()") { row ->
    row.get<String>(0)
}

// Single parameter
val users = db.query("SELECT id, name FROM users WHERE active = $1", listOf(true)) { row ->
    User(
        id   = row.get<Int>("id"),
        name = row.get<String>("name")
    )
}

// Multiple parameters
val results = db.query(
    "SELECT id, name FROM users WHERE role = $1 AND active = $2",
    listOf("admin", true)
) { row ->
    User(row.get("id"), row.get("name"))
}
```

Use `queryOne` to get the first row or `null`:

```kotlin
val user = db.queryOne(
    "SELECT id, name FROM users WHERE id = $1",
    listOf(42)
) { row ->
    User(row.get("id"), row.get("name"))
}
```

Use `row.getOrNull<T>()` for nullable columns. Column names are **case-insensitive**.

> **Note:** `queryOne` uses a wire-level cursor (`Execute(maxRows = 1)`) — the server stops sending rows after the first one, so no extra rows are transferred even for unbounded queries.

### Execute with RETURNING

Use `executeReturning` for `INSERT`, `UPDATE`, or `DELETE` statements that include a `RETURNING` clause. Using `execute` with `RETURNING` will crash the connection.

```kotlin
// Insert a row and get the generated id back
val newId = db.executeReturning(
    "INSERT INTO users (name, email) VALUES ($1, $2) RETURNING id",
    listOf("Alice", "alice@example.com")
) { row ->
    row.get<Int>("id")
}.first()

// Update and get the updated rows back
val updated = db.executeReturning(
    "UPDATE users SET active = $1 WHERE role = $2 RETURNING id, name",
    listOf(false, "guest")
) { row ->
    User(row.get("id"), row.get("name"))
}
```

### Streaming (queryFlow)

`queryFlow` returns a cold `Flow<T>` backed by a wire-level cursor. The server sends rows in chunks of `chunkSize` (default 50) using the PostgreSQL `Execute(maxRows = N)` / `PortalSuspended` mechanism. No results are buffered in memory beyond the current chunk.

```kotlin
import kotlinx.coroutines.flow.collect

// Stream all rows from a large table
db.queryFlow("SELECT id, payload FROM events ORDER BY id", chunkSize = 100) { row ->
    Event(row.get("id"), row.get("payload"))
}.collect { event ->
    process(event)
}

// With parameters
db.queryFlow(
    "SELECT id, payload FROM events WHERE tenant_id = $1 ORDER BY id",
    listOf(tenantId),
    chunkSize = 200
) { row ->
    Event(row.get("id"), row.get("payload"))
}.collect { event ->
    process(event)
}
```

> **Note:** `queryFlow` acquires a connection for the entire duration of the flow collection. The connection-acquire step is bounded by `acquireTimeout`, but execution is not — the flow can run as long as needed.

### Transactions

`inTransaction` takes an extension lambda on `CardioTransaction`. Inside the block, `this` is the transaction handle, so you can call `query`, `execute`, and `executeReturning` directly.

```kotlin
db.inTransaction {
    val id = executeReturning(
        "INSERT INTO users (name) VALUES ($1) RETURNING id",
        listOf("Alice")
    ) { row -> row.get<Int>("id") }.first()

    execute(
        "INSERT INTO audit (user_id, event) VALUES ($1, $2)",
        listOf(id, "created")
    )
}
```

#### Implicit transaction propagation

Any `db.query()`, `db.execute()`, or `db.executeReturning()` call made from within an `inTransaction` block automatically joins the active transaction — no need to pass a `tx` handle through your call stack.

```kotlin
suspend fun createUser(name: String): Int {
    return db.executeReturning(
        "INSERT INTO users (name) VALUES ($1) RETURNING id",
        listOf(name)
    ) { row -> row.get<Int>("id") }.first()
}

suspend fun createAuditLog(userId: Int, event: String) {
    db.execute(
        "INSERT INTO audit (user_id, event) VALUES ($1, $2)",
        listOf(userId, event)
    )
}

// Both helpers run inside the same transaction automatically
db.inTransaction {
    val id = createUser("Alice")   // uses the active tx
    createAuditLog(id, "created")  // also uses the same tx
}
```

#### Nested inTransaction

Calling `inTransaction` from within an already-active transaction joins the existing transaction rather than opening a nested one. The outer transaction controls commit/rollback.

```kotlin
db.inTransaction {
    execute("INSERT INTO users (name) VALUES ($1)", listOf("Alice"))

    db.inTransaction {
        // This joins the outer transaction — no nested BEGIN is sent
        execute("INSERT INTO audit (event) VALUES ($1)", listOf("user_created"))
    }
    // Single commit here covers both inserts
}
```

#### Explicit commit / rollback

```kotlin
db.inTransaction {
    execute("INSERT INTO drafts (body) VALUES ($1)", listOf(content))
    if (shouldPublish) {
        commit()   // explicit commit
    } else {
        rollback() // explicit rollback
    }
}
```

### LISTEN / NOTIFY

Cardio supports PostgreSQL's asynchronous `LISTEN`/`NOTIFY` mechanism natively.
`PgListener` owns a **single dedicated connection outside the pool** — it stays open indefinitely
waiting for notifications and reconnects automatically (with exponential back-off) on any failure.

#### Creating a listener

```kotlin
import io.github.blad3mak3r.cardio.core.PgListener

val listener = PgListener.connect {
    host     = "localhost"
    database = "mydb"
    username = "user"
    password = "secret"
}
```

The factory accepts the same DSL as `Cardio.new`. The listener starts with no active subscriptions; call `listen` to begin receiving.

#### Subscribing to channels

```kotlin
// One channel
listener.listen("orders")

// Several channels at once
listener.listen("orders", "shipments", "payments")
```

#### Collecting notifications

```kotlin
import io.github.blad3mak3r.cardio.protocol.PgNotification
import kotlinx.coroutines.flow.collect

// All channels — SharedFlow, supports multiple concurrent collectors
listener.notifications.collect { n: PgNotification ->
    println("[${n.channel}] ${n.payload}  (pid=${n.processId})")
}

// Filtered to one channel
listener.channel("orders").collect { n ->
    handleOrder(n.payload)
}
```

`notifications` is a `SharedFlow<PgNotification>` — it is hot and supports any number of concurrent collectors. Each collector receives every notification independently.

#### Sending notifications

```kotlin
// From the main pool connection — injection-safe via pg_notify
db.notify("orders", "new-order-42")

// Payload is optional (defaults to empty string)
db.notify("heartbeat")
```

`db.notify()` routes through the active transaction context when called inside `inTransaction`.
PostgreSQL defers delivery until the transaction commits, so listeners will only see the
notification after a successful commit.

```kotlin
db.inTransaction {
    execute("INSERT INTO orders (payload) VALUES ($1)", listOf(orderJson))
    db.notify("orders", orderId.toString())
    // notification is delivered only after this block commits
}
```

#### Unsubscribing

```kotlin
listener.unlisten("orders")
```

When the channel set changes, `PgListener` reconnects and re-subscribes to the remaining active channels automatically.

#### Cleanup

```kotlin
listener.close()
```

Cancels the receive loop and closes the dedicated connection. The corresponding `UNLISTEN *` is handled by the server when the TCP connection closes.

#### `PgNotification` fields

| Field | Type | Description |
|---|---|---|
| `channel` | `String` | The channel name on which the notification was sent |
| `payload` | `String` | Arbitrary string payload (empty string if none was provided) |
| `processId` | `Int` | Server PID of the session that called `NOTIFY` |

> **Note:** `PgListener` uses its own connection that is never returned to the pool.
> It does not count against `maxSize` and does not block other queries.
> Automatic reconnection uses exponential back-off starting at 500 ms, capped at 30 s.

---

### Repositories

```kotlin
class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {

    suspend fun findById(id: Int): User? =
        queryOne("SELECT id, name FROM users WHERE id = $1", listOf(id)) { row ->
            User(id = row.get("id"), name = row.get("name"))
        }

    suspend fun create(name: String): Int = inTransaction {
        executeReturning(
            "INSERT INTO users (name) VALUES ($1) RETURNING id",
            listOf(name)
        ) { row -> row.get<Int>("id") }.first()
    }

    suspend fun findAll(): List<User> =
        query("SELECT id, name FROM users ORDER BY id") { row ->
            User(row.get("id"), row.get("name"))
        }
}
```

### kotlinx.serialization

```kotlin
@Serializable
data class User(val id: Int, val name: String)

// Mapper-free single row
val user: User? = db.queryOne<User>(
    "SELECT id, name FROM users WHERE id = $1",
    listOf(42)
)

// Mapper-free list
val users: List<User> = db.query<User>("SELECT id, name FROM users WHERE active = $1", listOf(true))

// Manual decode (when you need access to the Row object)
val user = db.queryOne(
    "SELECT id, name FROM users WHERE id = $1",
    listOf(42)
) { row ->
    CardioSerializationFormat.decodeFromRow<User>(row)
}
```

### Arrays

Cardio supports PostgreSQL array parameters natively, enabling SQL functions like `ANY($1)`, `unnest($1)`, `= ALL($1)`, etc.

Pass a Kotlin `List<T>` or a primitive array and Cardio will encode it in the PostgreSQL binary array format automatically. When the array is itself a query parameter, wrap it in the outer params list:

```kotlin
// ANY($1) — filter by a set of ids
val users = db.query(
    "SELECT id, name FROM users WHERE id = ANY($1)",
    listOf(listOf(1, 2, 3))
) { row -> User(row.get("id"), row.get("name")) }

// unnest($1) — expand an array into rows
val ids = db.query(
    "SELECT * FROM unnest($1) AS id",
    listOf(listOf(10L, 20L, 30L))
) { row -> row.get<Long>("id") }

// Kotlin primitive arrays work too
db.execute("DELETE FROM sessions WHERE id = ANY($1)", listOf(intArrayOf(5, 6, 7)))

// Mixed queries — scalar and array params together
db.query(
    "SELECT * FROM events WHERE tenant_id = $1 AND status = ANY($2)",
    listOf(tenantId, listOf("active", "pending"))
) { row -> /* … */ }
```

Array results (columns with an array type) are decoded back to `List<T>`:

```kotlin
val row = db.queryOne("SELECT tags FROM posts WHERE id = $1", listOf(42)) { it }!!
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
    listOf(Param(myEnumList, ArrayCodec(PgOid.TEXT_ARRAY, MyEnumCodec)))
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
| [`INET`](https://www.postgresql.org/docs/current/datatype-net-types.html) | `io.github.blad3mak3r.cardio.protocol.PgInet` |
| [`CIDR`](https://www.postgresql.org/docs/current/datatype-net-types.html) | `io.github.blad3mak3r.cardio.protocol.PgInet` |
| [`MACADDR`](https://www.postgresql.org/docs/current/datatype-net-types.html) | `kotlin.String` (format: "08:00:2b:01:02:03") |
| [`MACADDR8`](https://www.postgresql.org/docs/current/datatype-net-types.html) | `kotlin.String` (format: "08:00:2b:01:02:03:04:05") |
| [`JSON`](https://www.postgresql.org/docs/current/datatype-json.html) | `kotlin.String` |
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

Built-in scalar codecs: `Int2`, `Int4`, `Int8`, `Float4`, `Float8`, `Numeric`, `Text`, `Varchar` (`CHARACTER VARYING`), `Bpchar` (`CHAR(n)`), `Bool`, `ByteArray`, `UUID` (`kotlin.uuid.Uuid`), `Instant` (`kotlin.time.Instant`), `Timestamp` (`kotlinx.datetime.LocalDateTime`), `LocalDate` (`kotlinx.datetime.LocalDate`), `Interval` (`PgInterval`), `Int4Range` (`PgRange<Int>`), `Int8Range` (`PgRange<Long>`), `NumRange` (`PgRange<BigDecimal>`), `TsRange` (`PgRange<LocalDateTime>`), `TsTzRange` (`PgRange<Instant>`), `DateRange` (`PgRange<LocalDate>`), `Inet` (`PgInet`), `Cidr` (`PgInet`), `MacAddr`, `MacAddr8`, `JSON`, `JSONB`.

Built-in array codecs (automatically selected when a `List<T>` or primitive array is passed): `Int2Array`, `Int4Array`, `Int8Array`, `Float4Array`, `Float8Array`, `NumericArray`, `TextArray`, `VarcharArray`, `BoolArray`, `UuidArray`, `TimestampArray`, `TimestamptzArray`, `IntervalArray`, `JsonArray`, `InetArray`, `CidrArray`, `MacAddrArray`, `MacAddr8Array`.

### Pool statistics

```kotlin
val stats = db.stats
println("Active: ${stats.activeConnections} / ${stats.totalConnections}")
println("Total acquired: ${stats.totalAcquired}, errors: ${stats.totalErrors}")
```

### Exception hierarchy

All Cardio exceptions extend `CardioException`, so you can catch the entire family with one handler or target a specific subtype:

```kotlin
import io.github.blad3mak3r.cardio.protocol.CardioException
import io.github.blad3mak3r.cardio.protocol.PgException
import io.github.blad3mak3r.cardio.protocol.connection.PgConnectException
import io.github.blad3mak3r.cardio.protocol.connection.PgSslException
import io.github.blad3mak3r.cardio.protocol.connection.PgPoolTimeoutException

try {
    db.query("SELECT * FROM non_existent_table") { it }
} catch (e: PgException) {
    // Server returned an error response (wrong SQL, constraint violation, …)
    println("SQL error ${e.sqlState}: ${e.message}")
} catch (e: PgPoolTimeoutException) {
    // All pool connections were busy; caller waited longer than acquireTimeout
    println("Pool exhausted: ${e.message}")
} catch (e: PgSslException) {
    // TLS negotiation failed (server declined SSL, cert invalid, hostname mismatch)
    println("SSL error: ${e.message}")
} catch (e: PgConnectException) {
    // Could not reach the server (TCP connect failed, startup timeout, wrong credentials)
    println("Connect error: ${e.message}")
} catch (e: CardioException) {
    // Any other Cardio error
    println("Cardio error: ${e.message}")
}
```

| Exception | Extends | When thrown |
|---|---|---|
| `CardioException` | `Exception` | Base class — never thrown directly |
| `PgException` | `CardioException` | Server returned an `ErrorResponse` |
| `PgConnectException` | `CardioException` | TCP connection or startup handshake failed |
| `PgSslException` | `PgConnectException` | TLS negotiation failed |
| `PgPoolTimeoutException` | `CardioException` | `acquireTimeout` expired waiting for a free connection |
| `PgConnectionCreationException` | `CardioException` | Pool exhausted all reconnect attempts |

---

## SSL / TLS

Cardio implements the PostgreSQL SSLRequest wire-protocol handshake and upgrades the TCP
connection to TLS via `ktor-network-tls`. Five modes are supported, matching the standard
`sslmode` semantics from `libpq`:

| Mode | Server SSL required | Certificate verified | Hostname verified |
|---|:---:|:---:|:---:|
| `DISABLE` | — | — | — |
| `PREFER` | — | — | — |
| `REQUIRE` | ✅ | — | — |
| `VERIFY_CA` | ✅ | ✅ | — |
| `VERIFY_FULL` | ✅ | ✅ | ✅ |

### Modes

- **`DISABLE`** — Plain TCP; no TLS handshake is performed.
- **`PREFER`** — Attempt TLS first; fall back silently to plain TCP if the server declines. No certificate validation (trust-all).
- **`REQUIRE`** — TLS is mandatory; throws `PgSslException` if the server does not support it. Server certificate is **not** verified (trust-all).
- **`VERIFY_CA`** — TLS is mandatory; the server certificate must be signed by the supplied CA (or the JVM default trust store when no CA is provided). Hostname is not checked.
- **`VERIFY_FULL`** — TLS is mandatory; the server certificate must be signed by the CA **and** the certificate's hostname must match the connection host (Subject Alternative Names checked first, CN as fallback per RFC 2818). For connections to IP addresses only iPAddress SANs are authoritative; CN fallback is prohibited per RFC 2818 §3.1.

### Configuration

#### Programmatic

```kotlin
import io.github.blad3mak3r.cardio.protocol.connection.Connection
import java.io.File

// Plain TCP (default)
val db = Cardio.new {
    host = "localhost"; database = "mydb"; username = "user"; password = "secret"
    ssl = Connection.SslMode.DISABLE
}

// Opportunistic TLS — falls back to plain if server has no SSL
val db = Cardio.new {
    host = "db.example.com"; database = "mydb"; username = "user"; password = "secret"
    ssl = Connection.SslMode.PREFER
}

// Require TLS, trust any certificate (no CA validation)
val db = Cardio.new {
    host = "db.example.com"; database = "mydb"; username = "user"; password = "secret"
    ssl = Connection.SslMode.REQUIRE
}

// Require TLS + verify certificate against a custom CA
val caPem = File("/etc/ssl/pg-ca.crt").readBytes()

val db = Cardio.new {
    host = "db.example.com"; database = "mydb"; username = "user"; password = "secret"
    ssl        = Connection.SslMode.VERIFY_CA
    sslRootCert = caPem               // PEM-encoded CA certificate (or bundle)
}

// Require TLS + verify certificate AND hostname (recommended for production)
val db = Cardio.new {
    host = "db.example.com"; database = "mydb"; username = "user"; password = "secret"
    ssl         = Connection.SslMode.VERIFY_FULL
    sslRootCert = caPem               // PEM-encoded CA certificate (or bundle)
}
```

#### Via URL

Pass `sslmode` as a query parameter (case-insensitive; libpq-style lowercase and camelCase are both accepted).

```kotlin
// PREFER
val db = Cardio.new {
    url("postgres://user:secret@db.example.com:5432/mydb?sslmode=prefer")
}

// VERIFY_FULL — CA cert supplied via query parameter
val db = Cardio.new {
    url("postgres://user:secret@db.example.com:5432/mydb?sslmode=verify-full&sslrootcertpath=/etc/ssl/pg-ca.crt")
}

// VERIFY_FULL — CA cert supplied programmatically
val db = Cardio.new {
    url("postgres://user:secret@db.example.com:5432/mydb?sslmode=verify-full")
    sslRootCert = File("/etc/ssl/pg-ca.crt").readBytes()
}
```

Valid `sslmode` values: `disable`, `prefer`, `require`, `verify-ca`, `verify-full`.

### `sslRootCert`

`sslRootCert` accepts a **PEM-encoded** CA certificate as a `ByteArray`. Multi-certificate
PEM bundles (e.g. an intermediate + root CA chain) are fully supported — all certificates
in the file are added to the in-memory trust store.

```kotlin
sslRootCert = File("/etc/ssl/certs/ca-bundle.pem").readBytes()
```

When `sslRootCert` is `null` the JVM's default trust store is used (applies to
`VERIFY_CA` and `VERIFY_FULL` only; ignored for the other modes).

### Error handling

SSL errors throw `PgSslException` (a subtype of `PgConnectException`):

```kotlin
import io.github.blad3mak3r.cardio.protocol.connection.PgConnectException
import io.github.blad3mak3r.cardio.protocol.connection.PgSslException

try {
    val db = Cardio.new {
        host = "localhost"; database = "mydb"; username = "user"; password = "secret"
        ssl = Connection.SslMode.REQUIRE
    }
} catch (e: PgSslException) {
    println("TLS negotiation failed: ${e.message}")
} catch (e: PgConnectException) {
    println("Connection failed: ${e.message}")
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
