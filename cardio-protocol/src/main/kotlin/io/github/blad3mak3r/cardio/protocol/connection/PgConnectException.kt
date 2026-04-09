package io.github.blad3mak3r.cardio.protocol.connection

/**
 * Thrown when a connection to the PostgreSQL server cannot be established.
 *
 * This wraps the underlying network or timeout error with a human-readable message
 * that includes the target host and port.
 *
 * @param host  Hostname or IP address of the PostgreSQL server.
 * @param port  TCP port of the PostgreSQL server.
 * @param cause The underlying exception that caused the connection failure.
 */
class PgConnectException(
    host: String,
    port: Int,
    cause: Throwable,
) : Exception("Cannot connect to PostgreSQL at $host:$port — ${cause.message}", cause)