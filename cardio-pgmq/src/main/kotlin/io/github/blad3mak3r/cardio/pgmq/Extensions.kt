package io.github.blad3mak3r.cardio.pgmq

import io.github.blad3mak3r.cardio.core.Cardio

/** Creates a [PgmqClient] backed by this [Cardio] instance's connection pool. */
fun Cardio.pgmq(): PgmqClient = PgmqClient(this)

/**
 * Creates a [PgmqConsumer] for [queueName] that holds a dedicated connection outside the pool.
 *
 * The consumer starts polling immediately. Call [PgmqConsumer.close] when done.
 *
 * @param queueName         Name of the PGMQ queue to consume.
 * @param visibilityTimeout Seconds a message remains invisible after being read. Defaults to `30`.
 * @param batchSize         Number of messages to fetch per poll. Defaults to `1`.
 * @param maxPollSeconds    Server-side long-poll duration in seconds. Defaults to `5`.
 * @param pollIntervalMs    Polling interval in milliseconds during the server-side wait. Defaults to `100`.
 */
fun Cardio.pgmqConsumer(
    queueName: String,
    visibilityTimeout: Int = 30,
    batchSize: Int = 1,
    maxPollSeconds: Int = 5,
    pollIntervalMs: Int = 100,
): PgmqConsumer = PgmqConsumer.create(this, queueName, visibilityTimeout, batchSize, maxPollSeconds, pollIntervalMs)
