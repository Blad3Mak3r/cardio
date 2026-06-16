package io.github.blad3mak3r.cardio.pgmq

import io.github.blad3mak3r.cardio.protocol.Row
import kotlin.time.Instant

data class QueueInfo(
    val queueName: String,
    val createdAt: Instant,
    val isPartitioned: Boolean,
    val isUnlogged: Boolean,
)

internal fun Row.toQueueInfo() = QueueInfo(
    queueName     = get("queue_name"),
    createdAt     = get("created_at"),
    isPartitioned = get("is_partitioned"),
    isUnlogged    = get("is_unlogged"),
)
