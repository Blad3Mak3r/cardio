package io.github.blad3mak3r.cardio.protocol

/**
 * Abstract base class for all exceptions thrown by the Cardio library.
 *
 * The full hierarchy is:
 * ```
 * CardioException
 * ├── PgException                  — server returned an ErrorResponse
 * └── PgConnectException           — TCP connection / startup failed
 *     └── PgSslException           — TLS negotiation failed
 * PgPoolTimeoutException           — pool acquire timed out
 * PgConnectionCreationException    — pool exhausted all reconnect attempts
 * ```
 *
 * Catch `CardioException` to handle any Cardio error uniformly.
 * Catch `PgConnectException` to handle both plain-TCP and SSL failures
 * (since [io.github.blad3mak3r.cardio.protocol.connection.PgSslException] extends it).
 */
abstract class CardioException(message: String, cause: Throwable? = null) : Exception(message, cause)
