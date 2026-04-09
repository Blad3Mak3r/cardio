package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.connection.Connection
import java.io.File
import java.net.URI

/**
 * Populates this [Cardio.Configuration] from a PostgreSQL connection URL.
 *
 * Supported URL scheme: `postgres://user:password@host[:port]/database[?params]` or
 * `postgresql://…`.
 *
 * The following query parameters are recognised (keys are case-insensitive):
 * | Parameter         | Maps to                                                                 |
 * |-------------------|-------------------------------------------------------------------------|
 * | `sslmode`         | [Cardio.Configuration.ssl] (`disable`, `prefer`, `require`, `verify-ca`, `verify-full`) |
 * | `sslrootcertpath` | Reads the PEM file at the given path into [Cardio.Configuration.sslRootCert] |
 * | `applicationname` | [Cardio.Configuration.applicationName]                                  |
 *
 * @throws IllegalArgumentException if the URL is malformed or contains an unknown scheme.
 * @throws IllegalStateException    if required URL components are missing or if a referenced
 *                                  certificate file cannot be read.
 */
fun Cardio.Configuration.url(string: String) {
    val url = URI.create(string)

    fun parsePostgresUrl(url: URI) {
        val userInfo = url.userInfo ?: error("No user info found")
        val (user, password) = userInfo.split(":", limit = 2)
        this.username = user
        this.password = password

        this.host = url.host ?: error("No host found in URL (did you use postgres://host/db syntax?)")
        if (url.port >= 0) this.port = url.port

        val path = url.path
        this.database = path.substringAfter("/").ifBlank { error("No database name found in URL path") }

        // Keys are lowercased so that both libpq-style ("sslmode") and camelCase ("sslMode")
        // parameter names are accepted interchangeably.
        val params = url.query?.split("&")
            ?.filter { it.isNotEmpty() }
            ?.associate { param ->
                val eq = param.indexOf('=')
                if (eq == -1) param.lowercase() to ""
                else param.substring(0, eq).lowercase() to param.substring(eq + 1)
            } ?: emptyMap()

        this.ssl = when (val sslString = params["sslmode"]?.lowercase()) {
            "require"     -> Connection.SslMode.REQUIRE
            "prefer"      -> Connection.SslMode.PREFER
            "disable"     -> Connection.SslMode.DISABLE
            "verify-ca"   -> Connection.SslMode.VERIFY_CA
            "verify-full" -> Connection.SslMode.VERIFY_FULL
            null          -> Connection.SslMode.DISABLE
            else -> error("Invalid sslmode value: $sslString " +
                "(valid: disable, prefer, require, verify-ca, verify-full)")
        }

        params["sslrootcertpath"]?.let { certPath ->
            val file = File(certPath)
            if (!file.isFile) error("sslrootcertpath '$certPath' does not exist or is not a file")
            this.sslRootCert = try {
                file.readBytes()
            } catch (e: Exception) {
                throw IllegalStateException("sslrootcertpath '$certPath' could not be read: ${e.message}", e)
            }
        }

        params["applicationname"]?.let { this.applicationName = it }
    }

    // TODO: unix socket connection
    fun parseUnixSocket(url: URI) {
        error("Unix socket connections are not supported yet")
    }

    when (val scheme = url.scheme) {
        "postgres", "postgresql" -> parsePostgresUrl(url)
        "unix" -> parseUnixSocket(url)
        else -> error("Unsupported URL schema: $scheme (valid: postgres, postgresql)")
    }
}