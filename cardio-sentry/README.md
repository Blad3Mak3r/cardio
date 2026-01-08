# Cardio Sentry

Cardio Sentry is a module for the Cardio library that provides Sentry integration for detailed and structured error reporting.

## Features

- **Easy Initialization:** Simple API to initialize Sentry with custom configuration
- **Structured Error Reporting:** Capture exceptions with additional context, tags, and extras
- **Coroutine Support:** Coroutine-aware exception handler for automatic error reporting
- **Flexible Configuration:** Full access to Sentry's configuration options

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("io.github.blad3mak3r.cardio:cardio-sentry:VERSION")
}
```

## Usage

### Initialize Sentry

Initialize Sentry at the start of your application:

```kotlin
CardioSentry.init {
    dsn = "https://your-sentry-dsn@sentry.io/project-id"
    environment = "production"
    release = "1.0.0"
    tracesSampleRate = 1.0
}
```

### Capture Exceptions

Capture exceptions with additional context:

```kotlin
try {
    // Your code that might throw an exception
    performDatabaseOperation()
} catch (e: Exception) {
    CardioSentry.captureException(e) {
        tag("component", "database")
        tag("operation", "query")
        context("query", mapOf(
            "sql" to "SELECT * FROM users",
            "params" to listOf(1, 2, 3)
        ))
        extra("user_id", userId)
    }
    throw e
}
```

### Capture Messages

Capture informational messages:

```kotlin
CardioSentry.captureMessage("User login successful", SentryLevel.INFO) {
    tag("event", "authentication")
    extra("user_id", userId)
}
```

### Coroutine Integration

Use the coroutine exception handler for automatic error reporting:

```kotlin
val scope = CoroutineScope(
    SupervisorJob() + 
    Dispatchers.Default + 
    CardioSentryExceptionHandler(additionalContext = mapOf(
        "service" to "user-service"
    ))
)

scope.launch {
    // Exceptions thrown here will be automatically captured by Sentry
    performAsyncOperation()
}
```

### Extension Functions

Use extension functions for convenient error reporting in suspend functions:

```kotlin
suspend fun performOperation() {
    try {
        // Your code
    } catch (e: Exception) {
        e.captureToSentry {
            tag("operation", "data-processing")
            extra("batch_size", 100)
        }
        throw e
    }
}

suspend fun logEvent() {
    captureSentryMessage("Processing completed") {
        tag("component", "processor")
        extra("items_processed", 1000)
    }
}
```

### Cleanup

Close Sentry when shutting down your application:

```kotlin
CardioSentry.close()
```

## Integration with Cardio Postgres

While `cardio-sentry` is a separate module and `cardio-postgres` does not depend on it, you can easily integrate them:

```kotlin
class UserRepository(db: MyDb) : CardioRepository<MyDb>(db) {
    suspend fun findUser(id: Int): User? {
        return try {
            query("SELECT * FROM users WHERE id = $1", listOf(id)) { row, _ ->
                User(
                    id = row.getAs<Int>("id"),
                    name = row.getAs<String>("name")
                )
            }.firstOrNull()
        } catch (e: Exception) {
            CardioSentry.captureException(e) {
                tag("repository", "user")
                tag("operation", "findUser")
                extra("user_id", id)
            }
            throw e
        }
    }
}
```

## License

Apache License 2.0
