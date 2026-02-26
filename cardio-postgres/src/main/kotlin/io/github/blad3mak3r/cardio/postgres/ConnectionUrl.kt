package io.github.blad3mak3r.cardio.postgres

import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.SslMode
import java.net.URI

/**
 * Parses a PostgreSQL connection URL and applies the resulting [PgConnectOptions]
 * to this [Cardio.Configuration].
 *
 * Supported schemes: `postgres`, `postgresql`.
 *
 * Format: `postgres://[user[:password]@]host[:port][/database][?param=value&...]`
 *
 * Recognised query parameters (case-insensitive):
 * - `sslmode` → enables SSL when set to `require`, `verify-ca` or `verify-full`
 *
 * Example:
 * ```kotlin
 * Cardio.create<MyDb> {
 *     url("postgres://user:secret@localhost:5432/mydb")
 * }
 * ```
 */
fun Cardio.Configuration.url(connectionUrl: String) {
    connectOptions = pgConnectOptionsFromUrl(connectionUrl)
}

/**
 * Parses a PostgreSQL connection URL into a [PgConnectOptions] instance.
 *
 * @param connectionUrl A URL of the form
 *   `postgres://[user[:password]@]host[:port][/database][?param=value&...]`
 * @throws IllegalArgumentException if the URL scheme is not `postgres` or `postgresql`.
 */
fun pgConnectOptionsFromUrl(connectionUrl: String): PgConnectOptions {
    val uri = URI(connectionUrl.trim())

    require(uri.scheme in listOf("postgres", "postgresql")) {
        "Unsupported scheme '${uri.scheme}'. Expected 'postgres' or 'postgresql'."
    }

    return PgConnectOptions().apply {
        host = uri.host ?: "localhost"
        port = uri.port.takeIf { it > 0 } ?: 5432

        // Parse userinfo — "user:password" or just "user"
        uri.userInfo?.let { info ->
            val parts = info.split(":", limit = 2)
            user = parts[0]
            if (parts.size == 2) password = parts[1]
        }

        // Strip leading '/' from the path to get the database name
        uri.path?.trimStart('/')?.takeIf { it.isNotEmpty() }?.let { db ->
            database = db
        }

        // Handle query parameters
        uri.query?.split("&")?.forEach { param ->
            val (key, value) = param.split("=", limit = 2).let {
                it[0].lowercase() to (if (it.size == 2) it[1] else "")
            }
            when (key) {
                "sslmode" -> {
                    this.sslMode = SslMode.of(value)
                }
            }
        }
    }
}

