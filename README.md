# Cardio
Cardio is a lightweight Kotlin library designed to simplify interaction with PostgreSQL databases using R2DBC reactively and with coroutines.
## Features
- **Reactive and Non-Blocking:** Built on R2DBC for high performance.
- **Coroutines Support:** API designed to be used with Kotlin Coroutines.
- **Transaction Management:** Simple transaction handling with `inTransaction` blocks.
- **Repositories:** `CardioRepository` base class for structuring data access.
- **Connection Pooling:** Automatic connection pool management.
## Usage
### Configuration
To get started, create an instance of `Cardio` by configuring the PostgreSQL connection and the pool:
```kotlin
val cardio = Cardio.create {
    r2dbcConfig = {
        host("localhost")
        port(5432)
        database("my_database")
        username("user")
        password("password")
    }
    poolConfig = {
        maxSize(10)
    }
}
```
### Queries and Transactions
You can safely execute queries within a transaction:
```kotlin
cardio.inTransaction { tx ->
    // Execute a query
    val users = tx.query("SELECT * FROM users WHERE active = $1", listOf(true)) { row, _ ->
        // Map results
        User(
            id = row.getAs<Int>("id"),
            name = row.getAs<String>("name")
        )
    }
    // Execute an update
    tx.execute("UPDATE users SET last_login = NOW() WHERE active = $1", listOf(true))
}
```
### Repositories
It is recommended to extend `CardioRepository` to encapsulate data access logic:
```kotlin
class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {
    suspend fun findById(id: Int): User? {
        return query("SELECT * FROM users WHERE id = $1", listOf(id)) { row, _ ->
            User(
                id = row.getAs<Int>("id"),
                name = row.getAs<String>("name")
            )
        }.firstOrNull()
    }
    suspend fun create(name: String): Long {
        return execute("INSERT INTO users (name) VALUES ($1)", listOf(name))
    }
}
```
### Extending Cardio
The `Cardio` class is `open`, allowing you to extend it to create a custom database context. This is useful for creating strongly-typed repositories that depend on your specific database class.

```kotlin
// Define your custom database class
class MyDb(pool: ConnectionPool) : Cardio(pool)

// Define a repository that requires MyDb
class MyRepo(db: MyDb) : CardioRepository<MyDb>(db) {
    // ...
}
```
## Useful Extensions
The library includes extensions to facilitate retrieving data from rows (`Row`):
- `row.getAs<T>("column_name")`: Gets the column value, throws error if null.
- `row.getAsNullable<T>("column_name")`: Gets the column value, allows nulls.
