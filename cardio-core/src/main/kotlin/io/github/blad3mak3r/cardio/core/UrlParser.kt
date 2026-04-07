package io.github.blad3mak3r.cardio.core

import io.github.blad3mak3r.cardio.protocol.connection.Connection
import java.io.File
import java.net.URI

fun Cardio.Configuration.url(string: String) {
    val url = URI.create(string)

    fun parsePostgresUrl(url: URI) {
        val userInfo = url.userInfo ?: error("No user info found")
        val (user, password) = userInfo.split(":", limit = 2)
        this.username = user
        this.password = password

        this.host = url.host
        this.port = url.port

        val path = url.path
        this.database = path.substringAfter("/").ifBlank { error("No database name found in URL path") }

        val params = url.query?.split("&")?.associate { param ->
            val (key, value) = param.split("=", limit = 2)
            key to value
        } ?: emptyMap()

        this.ssl = when (val sslString = params["sslMode"]?.lowercase()) {
            "require"     -> Connection.SslMode.REQUIRE
            "prefer"      -> Connection.SslMode.PREFER
            "disable"     -> Connection.SslMode.DISABLE
            "verify-ca"   -> Connection.SslMode.VERIFY_CA
            "verify-full" -> Connection.SslMode.VERIFY_FULL
            null          -> Connection.SslMode.DISABLE
            else -> error("Invalid sslMode value: $sslString " +
                "(valid: disable, prefer, require, verify-ca, verify-full)")
        }

        params["sslRootCertPath"]?.let { certPath ->
            val file = File(certPath)
            if (!file.isFile) error("sslRootCertPath '$certPath' does not exist or is not a file")
            this.sslRootCert = file.readBytes()
        }

        params["applicationName"]?.let { this.applicationName = it }
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