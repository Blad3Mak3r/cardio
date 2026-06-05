package io.github.blad3mak3r.cardio.pgmq

import io.github.blad3mak3r.cardio.protocol.Row
import kotlin.time.Instant

data class QueueMetrics(
    val queueName: String,
    val queueLength: Long,
    val newestMsgAgeSec: Int?,
    val oldestMsgAgeSec: Int?,
    val totalMessages: Long,
    val scrapeTime: Instant,
    val queueVisibleLength: Long,
)

internal fun Row.toQueueMetrics() = QueueMetrics(
    queueName          = get("queue_name"),
    queueLength        = get("queue_length"),
    newestMsgAgeSec    = getOrNull("newest_msg_age_sec"),
    oldestMsgAgeSec    = getOrNull("oldest_msg_age_sec"),
    totalMessages      = get("total_messages"),
    scrapeTime         = get("scrape_time"),
    queueVisibleLength = get("queue_visible_length"),
)
