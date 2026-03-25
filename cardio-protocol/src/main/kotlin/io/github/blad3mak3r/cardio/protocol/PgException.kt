package io.github.blad3mak3r.cardio.protocol

class PgException(
    val severity: String,
    val sqlState: String,
    override val message: String,
    val detail: String? = null,
    val hint: String? = null,
) : Exception("[$severity/$sqlState] $message${detail?.let { " — $it" } ?: ""}")