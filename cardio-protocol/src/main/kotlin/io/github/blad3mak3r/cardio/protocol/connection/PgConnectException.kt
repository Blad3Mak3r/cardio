package io.github.blad3mak3r.cardio.protocol.connection

import io.github.blad3mak3r.cardio.protocol.CardioException

/**
 * Thrown when a connection to the PostgreSQL server cannot be established.
 *
 * [PgSslException] extends this class — a single `catch (e: PgConnectException)` handles
 * both plain-TCP and SSL connection failures.
 *
 * @param message Human-readable description of the failure.
 * @param cause   The underlying exception that caused the connection failure, if any.
 */
open class PgConnectException(
    message: String,
    cause: Throwable? = null,
) : CardioException(message, cause) {

    companion object {
        /** Convenience factory that formats the standard host:port message. */
        operator fun invoke(host: String, port: Int, cause: Throwable): PgConnectException =
            PgConnectException(
                "Cannot connect to PostgreSQL at $host:$port — ${cause.message}",
                cause
            )
    }
}
