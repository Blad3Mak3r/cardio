package io.github.blad3mak3r.cardio.protocol.connection

import kotlin.time.Duration

class PgPoolTimeoutException(
    timeout: Duration, maxSize: Int, pending: Int,
) : Exception(
    "Timed out after $timeout waiting for a connection " +
            "(maxSize=$maxSize, pending=$pending)"
)

class PgConnectionCreationException(
    host: String, port: Int, attempts: Int, cause: Throwable,
) : Exception(
    "Failed to connect to $host:$port after $attempts attempts — ${cause.message}", cause
)