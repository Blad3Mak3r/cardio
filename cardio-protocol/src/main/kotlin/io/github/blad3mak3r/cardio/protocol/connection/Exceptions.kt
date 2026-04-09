package io.github.blad3mak3r.cardio.protocol.connection

import kotlin.time.Duration

/**
 * Thrown when all available connections in the pool are in use and a new connection
 * could not be acquired within the configured timeout.
 *
 * @param timeout  The duration the caller waited before timing out.
 * @param maxSize  Maximum number of connections allowed by the pool.
 * @param pending  Number of other callers waiting for a connection at the time of the timeout.
 */
class PgPoolTimeoutException(
    timeout: Duration, maxSize: Int, pending: Int,
) : Exception(
    "Timed out after $timeout waiting for a connection " +
            "(maxSize=$maxSize, pending=$pending)"
)

/**
 * Thrown when the [ConnectionPool] fails to open a new database connection after
 * exhausting all retry attempts.
 *
 * @param host     Hostname or IP address of the PostgreSQL server.
 * @param port     TCP port of the PostgreSQL server.
 * @param attempts Maximum number of connection attempts that were made.
 * @param cause    The last exception encountered during the final attempt.
 */
class PgConnectionCreationException(
    host: String, port: Int, attempts: Int, cause: Throwable,
) : Exception(
    "Failed to connect to $host:$port after $attempts attempts — ${cause.message}", cause
)