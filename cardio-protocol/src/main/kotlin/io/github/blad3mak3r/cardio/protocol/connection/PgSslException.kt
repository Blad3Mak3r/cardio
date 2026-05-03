package io.github.blad3mak3r.cardio.protocol.connection

/**
 * Thrown when the TLS/SSL negotiation with the PostgreSQL server fails.
 *
 * Extends [PgConnectException] because an SSL failure is a connection failure —
 * a single `catch (e: PgConnectException)` handles both plain-TCP and SSL failures.
 *
 * @param message Human-readable description of the failure.
 * @param cause   The underlying exception that caused this failure, if any.
 */
class PgSslException(message: String, cause: Throwable? = null) : PgConnectException(message, cause)
