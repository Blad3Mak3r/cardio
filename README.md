# Cardio
Cardio is a lightweight Kotlin library designed to simplify interaction with PostgreSQL databases using R2DBC reactively and with coroutines.

## Modules

- **cardio-postgres**: Core PostgreSQL integration with R2DBC and coroutines
- **cardio-sentry**: Optional Sentry integration for detailed error reporting

## Features
- **Reactive and Non-Blocking:** Built on R2DBC for high performance.
- **Coroutines Support:** API designed to be used with Kotlin Coroutines.
- **Transaction Management:** Simple transaction handling with `inTransaction` blocks.
- **Repositories:** `CardioRepository` base class for structuring data access.
- **Connection Pooling:** Automatic connection pool management.
- **Enhanced Error Handling:** Structured exceptions with detailed context for easier debugging.
- **Optional Sentry Integration:** Separate module for error tracking and monitoring.
## Usage
### Configuration
To get started, create an instance of `Cardio` by configuring the PostgreSQL connection and the pool:
```kotlin
val cardio = Cardio.create<Cardio> {
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

// Create an instance using the generic create method
val myDb = Cardio.create<MyDb> {
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

// Define a repository that requires MyDb
class MyRepo(db: MyDb) : CardioRepository<MyDb>(db) {
    suspend fun customOperation() {
        // Your custom operations here
    }
}

// Use the repository with your custom database
val myRepo = MyRepo(myDb)
```

The generic `create<T>` method uses reflection to instantiate your custom `Cardio` subclass, automatically handling the connection pool configuration and initialization.

## Error Handling

Cardio provides structured exceptions with detailed context to help you quickly identify and fix issues:

### Exception Types

- **CardioQueryException**: Thrown when a query fails, includes the SQL query and parameters
- **CardioExecutionException**: Thrown when a statement execution fails, includes the statement and parameters
- **CardioTransactionException**: Thrown when a transaction fails with context about the failure
- **CardioNullColumnException**: Thrown when accessing a null column as non-nullable, shows available columns
- **CardioColumnNotFoundException**: Thrown when a column doesn't exist, lists available columns to help identify typos

### Example

```kotlin
try {
    val user = query("SELECT * FROM users WHERE id = $1", listOf(userId)) { row, _ ->
        User(
            id = row.getAs<Int>("id"),
            name = row.getAs<String>("name")
        )
    }.firstOrNull()
} catch (e: CardioQueryException) {
    // Exception includes query, parameters, and cause
    logger.error("Query failed: ${e.message}")
    // Output: Query failed: Failed to execute query: ...
    // Query: SELECT * FROM users WHERE id = $1
    // Parameters: [123]
}
```

## Sentry Integration

For production applications, you can add the `cardio-sentry` module to track errors with Sentry:

```kotlin
dependencies {
    implementation("io.github.blad3mak3r.cardio:cardio-postgres:VERSION")
    implementation("io.github.blad3mak3r.cardio:cardio-sentry:VERSION")
}
```

See [cardio-sentry/README.md](cardio-sentry/README.md) for detailed usage instructions.

## Useful Extensions
The library includes extensions to facilitate retrieving data from rows (`Row`):
- `row.getAs<T>("column_name")`: Gets the column value, throws error if null.
- `row.getAsNullable<T>("column_name")`: Gets the column value, allows nulls.
