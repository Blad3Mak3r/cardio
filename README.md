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

The `cardio-sentry` module provides automatic error tracking and enrichment for production applications. If you're already using `cardio-postgres`, adding Sentry integration is straightforward.

### Why use cardio-sentry?

- **Automatic Error Enrichment**: All Cardio exceptions are automatically enriched with SQL queries, parameters, and context
- **Zero Configuration**: Works with your existing Sentry setup - no additional initialization needed
- **Production Ready**: Built on Sentry SDK 8.29.0 with proper error handling
- **Non-Intrusive**: Doesn't interfere with your existing error handling or Sentry configuration

### Installation

If you're already using `cardio-postgres`, simply add the `cardio-sentry` dependency:

```kotlin
dependencies {
    implementation("io.github.blad3mak3r.cardio:cardio-postgres:VERSION")
    implementation("io.github.blad3mak3r.cardio:cardio-sentry:VERSION")  // Add this line
}
```

### Setup for Existing Projects

If you already have Sentry configured in your application, you only need to register Cardio's enrichment callback:

```kotlin
import io.sentry.Sentry
import io.github.blad3mak3r.cardio.sentry.CardioSentryBeforeSend

// Your existing Sentry initialization
Sentry.init { options ->
    options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
    options.environment = "production"
    options.release = "1.0.0"
    // ... your other Sentry configuration
    
    // Add this line to enable automatic Cardio exception enrichment
    options.beforeSend = CardioSentryBeforeSend()
}
```

### Setup for New Projects

If you don't have Sentry yet, here's a complete setup example:

```kotlin
import io.sentry.Sentry
import io.github.blad3mak3r.cardio.sentry.CardioSentryBeforeSend

fun main() {
    // Initialize Sentry with Cardio integration
    Sentry.init { options ->
        options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
        options.environment = "production"
        options.release = "1.0.0"
        options.tracesSampleRate = 1.0
        
        // Enable automatic Cardio exception enrichment
        options.beforeSend = CardioSentryBeforeSend()
    }
    
    // Your application code
}
```

### What Gets Tracked

Once configured, all Cardio exceptions will be automatically sent to Sentry with enriched information:

- **CardioQueryException**: SQL query + parameters + error details
- **CardioExecutionException**: Statement + parameters + affected rows
- **CardioTransactionException**: Transaction state + rollback information
- **CardioNullColumnException**: Column name + available columns
- **CardioColumnNotFoundException**: Requested column + available columns

**No manual exception capture needed!** The library handles everything automatically.

### Example

```kotlin
class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {
    suspend fun findById(id: Int): User? {
        // If an error occurs here, it will be automatically sent to Sentry
        // with the full query, parameters, and context
        return query("SELECT * FROM users WHERE id = $1", listOf(id)) { row, _ ->
            User(
                id = row.getAs<Int>("id"),
                name = row.getAs<String>("name")
            )
        }.firstOrNull()
    }
}
```

For more details and advanced usage (like coroutine integration), see [cardio-sentry/README.md](cardio-sentry/README.md).

## Useful Extensions
The library includes extensions to facilitate retrieving data from rows (`Row`):
- `row.getAs<T>("column_name")`: Gets the column value, throws error if null.
- `row.getAsNullable<T>("column_name")`: Gets the column value, allows nulls.
