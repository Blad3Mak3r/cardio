package io.github.blad3mak3r.cardio.protocol

/**
 * A notification received from the PostgreSQL server via the `LISTEN`/`NOTIFY` mechanism.
 *
 * @param processId The server-assigned process ID of the session that sent the notification.
 * @param channel   The name of the channel on which the notification was sent.
 * @param payload   The optional payload string attached to the notification (empty string if none).
 */
data class PgNotification(
    val processId: Int,
    val channel: String,
    val payload: String,
)
