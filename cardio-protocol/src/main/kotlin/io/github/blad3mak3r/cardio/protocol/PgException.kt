package io.github.blad3mak3r.cardio.protocol

/**
 * Exception thrown when the PostgreSQL server returns an error response.
 *
 * The server encodes errors as `ErrorResponse` messages containing a set of structured
 * fields (see [ErrorField]). This exception surfaces the most relevant fields as typed
 * properties and includes both [severity] and [sqlState] in the exception message.
 *
 * @property severity  Severity label from the server (e.g. `ERROR`, `FATAL`, `PANIC`).
 * @property sqlState  Five-character SQLSTATE code (e.g. `42P01` for undefined table).
 * @property message   Primary human-readable error description.
 * @property detail    Optional secondary message with additional context.
 * @property hint      Optional suggestion on how to resolve the error.
 */
class PgException(
    val severity: String,
    val sqlState: String,
    override val message: String,
    val detail: String? = null,
    val hint: String? = null,
) : Exception("[$severity/$sqlState] $message${detail?.let { " — $it" } ?: ""}")