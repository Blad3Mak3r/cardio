# Migration Guide: R2DBC → Vert.x PG Client 5.x

This document describes the breaking changes and migration steps required to upgrade
`cardio-postgres` from `r2dbc-postgresql` to `io.vertx:vertx-pg-client:5.0.8`.

---

## Why migrate?

| | r2dbc-postgresql (old) | vertx-pg-client (new) |
|---|---|---|
| Maintenance | ⚠️ Stale, known bugs | ✅ Actively maintained (Eclipse Vert.x) |
| Async model | Reactive Streams / Reactor | Vert.x Future + Kotlin coroutines |
| Extra dependencies | `reactor-core`, `r2dbc-spi`, `r2dbc-pool` | None beyond Vert.x itself |
| Connection pooling | External (`r2dbc-pool`) | Built-in (`Pool`) |
| PostgreSQL type support | ⚠️ Known mapping issues | ✅ Native wire-protocol driver |

---

## Dependency changes

### `gradle/libs.versions.toml`

```diff
 [versions]
-r2dbc-pool     = "1.0.2.RELEASE"
-r2dbc-postgres = "1.1.1.RELEASE"
+vertx           = "5.0.8"

 [libraries]
-coroutines-reactor = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-reactor", version.ref = "coroutines" }
-r2dbc-pool         = { module = "io.r2dbc:r2dbc-pool",          version.ref = "r2dbc-pool" }
-r2dbc-postgres     = { module = "org.postgresql:r2dbc-postgresql", version.ref = "r2dbc-postgres" }
+vertx-pg-client         = { module = "io.vertx:vertx-pg-client",               version.ref = "vertx" }
+vertx-kotlin-coroutines = { module = "io.vertx:vertx-lang-kotlin-coroutines",  version.ref = "vertx" }
```

### `cardio-postgres/build.gradle.kts`

```diff
-api(libs.r2dbc.pool)
-api(libs.r2dbc.postgres)
-implementation(libs.coroutines.reactor)
+api(libs.vertx.pg.client)
+implementation(libs.vertx.kotlin.coroutines)
```

---

## API changes

### 1. Configuration block

The `r2dbcConfig` / `poolConfig` lambdas have been replaced by two plain data
properties that accept Vert.x option objects directly.

**Before**
```kotlin
Cardio.create<MyDb> {
    r2dbcConfig = {
        host("localhost")
        port(5432)
        database("mydb")
        username("user")
        password("secret")
    }
    poolConfig = {
        maxSize(10)
    }
}
```

**After**
```kotlin
Cardio.create<MyDb> {
    connectOptions = PgConnectOptions().apply {
        host = "localhost"
        port = 5432
        database = "mydb"
        user = "user"       // note: "user" not "username"
        password = "secret"
    }
    poolOptions = PoolOptions().apply {
        maxSize = 10
    }
}
```

> **Imports needed:**
> ```kotlin
> import io.vertx.pgclient.PgConnectOptions
> import io.vertx.sqlclient.PoolOptions
> ```

---

### 2. Custom `Cardio` subclass constructor

The constructor parameter type changed from `ConnectionPool` (R2DBC) to `Pool` (Vert.x).

**Before**
```kotlin
import io.r2dbc.pool.ConnectionPool

class MyDb(pool: ConnectionPool) : Cardio(pool)
```

**After**
```kotlin
import io.vertx.sqlclient.Pool

class MyDb(pool: Pool) : Cardio(pool)
```

---

### 3. `Row` type in `transform` lambdas

The `Row` type used in `query { row, metadata -> ... }` changed package,
and `RowMetadata` has been removed (metadata is available directly on `Row` in Vert.x).

**Before**
```kotlin
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata

suspend fun findById(id: Long) = query(
    stmt = "SELECT id, name FROM users WHERE id = $1",
    args = listOf(id)
) { row: Row, _: RowMetadata ->
    User(
        id   = row.getAs("id"),
        name = row.getAs("name")
    )
}
```

**After**
```kotlin
import io.github.blad3mak3r.cardio.postgres.Row  // typealias re-exported by cardio

suspend fun findById(id: Long) = query(
    stmt = "SELECT id, name FROM users WHERE id = $1",
    args = listOf(id)
) { row: Row ->                  // RowMetadata parameter removed
    User(
        id   = row.getAs("id"),
        name = row.getAs("name")
    )
}
```

> `cardio-postgres` re-exports `Row` as a public `typealias` in the
> `io.github.blad3mak3r.cardio.postgres` package, so **you do not need to import
> `io.vertx.sqlclient.Row` directly**. Just import the cardio alias (or omit the
> explicit type annotation entirely and let type inference handle it).
>
> `getAs<T>()` and `getAsNullable<T>()` extension functions work exactly the
> same as before — only the import changes.

---

### 4. SQL query syntax — no changes required ✅

Vert.x PG Client uses the same native PostgreSQL positional parameter syntax (`$1`, `$2`, …)
as R2DBC did, so **all existing queries are 100% compatible without modification**.

---

## Full import replacement reference

| Remove | Add |
|---|---|
| `import io.r2dbc.pool.ConnectionPool` | `import io.vertx.sqlclient.Pool` |
| `import io.r2dbc.spi.Row` | `import io.github.blad3mak3r.cardio.postgres.Row` *(typealias — or omit entirely)* |
| `import io.r2dbc.spi.RowMetadata` | *(remove — no longer needed)* |
| `import io.r2dbc.postgresql.PostgresqlConnectionConfiguration` | *(remove)* |
| `import io.r2dbc.postgresql.PostgresqlConnectionFactory` | *(remove)* |

---

## Checklist

- [ ] Update `gradle/libs.versions.toml` (versions + libraries)
- [ ] Update `build.gradle.kts` (swap dependencies)
- [ ] Replace `r2dbcConfig`/`poolConfig` blocks with `connectOptions`/`poolOptions`
- [ ] Change subclass constructor from `ConnectionPool` → `Pool`
- [ ] Update `transform` lambda signatures: remove `RowMetadata` second parameter
- [ ] Update all `import io.r2dbc.*` → `import io.vertx.*`
- [ ] Run `./gradlew compileKotlin compileTestKotlin` and verify zero errors

