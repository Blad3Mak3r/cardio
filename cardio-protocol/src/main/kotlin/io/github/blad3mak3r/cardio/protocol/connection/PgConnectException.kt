package io.github.blad3mak3r.cardio.protocol.connection

class PgConnectException(
    host: String,
    port: Int,
    cause: Throwable,
) : Exception("Cannot connect to PostgreSQL at $host:$port — ${cause.message}", cause)