# Cardio Sentry

Cardio Sentry is a module for the Cardio library that provides automatic Sentry integration for detailed and structured error reporting.

## Features

- **Automatic Error Enrichment:** Automatically structures Cardio exceptions with query details, parameters, and context
- **Zero Configuration:** Works with your existing Sentry setup - no additional initialization needed
- **Coroutine Support:** Optional coroutine exception handler for automatic error reporting
- **Non-Intrusive:** Uses your application's Sentry configuration - cardio-sentry doesn't initialize Sentry

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("io.github.blad3mak3r.cardio:cardio-sentry:VERSION")
}
```

## Usage

### Initialize Sentry in Your Application

First, initialize Sentry in your application as you normally would, and register the Cardio BeforeSend callback:

```kotlin
import io.sentry.Sentry
import io.github.blad3mak3r.cardio.sentry.CardioSentryBeforeSend

fun main() {
    // Initialize Sentry with your configuration
    Sentry.init { options ->
        options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
        options.environment = "production"
        options.release = "1.0.0"
        options.tracesSampleRate = 1.0
        
        // Register Cardio's BeforeSend callback for automatic enrichment
        options.beforeSend = CardioSentryBeforeSend()
    }
    
    // Your application code
}
```

### Automatic Exception Enrichment

Once configured, all Cardio exceptions will be automatically enriched with structured data:

```kotlin
class UserRepository(db: Cardio) : CardioRepository<Cardio>(db) {
    suspend fun findById(id: Int): User? {
        // Exceptions are automatically captured and enriched with:
        // - Query/statement SQL
        // - Parameters
        // - Column information (for column errors)
        // - Transaction context
        return query("SELECT * FROM users WHERE id = $1", listOf(id)) { row, _ ->
            User(
                id = row.getAs<Int>("id"),
                name = row.getAs<String>("name")
            )
        }.firstOrNull()
    }
}
```

When an exception occurs, Sentry will receive:
- **Tags**: `library=cardio`, `cardio.exception_type=query`, `cardio.query_hash=...`
- **Extras**: Full SQL query, parameters, column names (when applicable)
- **Fingerprints**: Automatic grouping by query structure

### Coroutine Integration (Optional)

For automatic capture of uncaught coroutine exceptions:

```kotlin
import io.github.blad3mak3r.cardio.sentry.CardioSentryExceptionHandler

val scope = CoroutineScope(
    SupervisorJob() + 
    Dispatchers.Default + 
    CardioSentryExceptionHandler(additionalContext = mapOf(
        "service" to "user-service"
    ))
)

scope.launch {
    // Uncaught exceptions will be automatically reported to Sentry
    performDatabaseOperation()
}
```

### Combining with Your Own BeforeSend

If you have your own BeforeSend logic, you can chain them:

```kotlin
Sentry.init { options ->
    options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
    
    val cardioBeforeSend = CardioSentryBeforeSend()
    options.beforeSend = SentryOptions.BeforeSendCallback { event, hint ->
        // Your custom processing
        val processed = myCustomProcessing(event)
        
        // Then let Cardio enrich Cardio-specific exceptions
        cardioBeforeSend.execute(processed, hint)
    }
}
```

## What Gets Enriched

### CardioQueryException
- Tags: `cardio.exception_type=query`, `cardio.query_hash`
- Extras: `cardio.query`, `cardio.params`, `cardio.params_count`

### CardioExecutionException
- Tags: `cardio.exception_type=execution`, `cardio.statement_hash`
- Extras: `cardio.statement`, `cardio.params`, `cardio.params_count`

### CardioNullColumnException
- Tags: `cardio.exception_type=null_column`, `cardio.column_name`
- Extras: `cardio.column_name`, `cardio.available_columns`

### CardioColumnNotFoundException
- Tags: `cardio.exception_type=column_not_found`, `cardio.column_name`
- Extras: `cardio.column_name`, `cardio.available_columns`

### CardioTransactionException
- Tags: `cardio.exception_type=transaction`

## Why This Design?

This design follows the principle that **users already have Sentry configured** in their applications. Cardio Sentry doesn't try to initialize or configure Sentry - it just provides structured data for Cardio-specific exceptions using your existing Sentry setup.

Benefits:
- No duplicate Sentry initialization
- Works with your existing Sentry configuration (DSN, environment, release, etc.)
- No manual exception capture needed
- Automatic enrichment without code changes
- Can be combined with your existing Sentry customizations

## License

Apache License 2.0
