package io.github.blad3mak3r.cardio.pgmq

import io.github.blad3mak3r.cardio.protocol.Row
import kotlin.time.Instant

data class PgmqMessage(
    val msgId: Long,
    val readCount: Int,
    val enqueuedAt: Instant,
    val lastReadAt: Instant?,
    val vt: Instant,
    val message: String,
    val headers: String?,
)

internal fun Row.toPgmqMessage() = PgmqMessage(
    msgId      = get("msg_id"),
    readCount  = get("read_ct"),
    enqueuedAt = get("enqueued_at"),
    lastReadAt = getOrNull("last_read_at"),
    vt         = get("vt"),
    message    = get("message"),
    headers    = getOrNull("headers"),
)
