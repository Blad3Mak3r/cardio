# cardio-pgmq

PGMQ extension support for [Cardio](https://github.com/Blad3Mak3r/cardio) — a coroutine-native PostgreSQL library for Kotlin.

[PGMQ](https://pgmq.github.io/pgmq/latest/) is a PostgreSQL extension that provides durable message queues backed by regular PostgreSQL tables, exposed entirely through SQL functions.

---

## Prerequisites

PGMQ must be installed in your PostgreSQL database:

```sql
CREATE EXTENSION IF NOT EXISTS pgmq;
```

---

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.blad3mak3r.cardio:cardio-pgmq:<version>")
}
```

---

## Quick start

```kotlin
val db = Cardio.new {
    host     = "localhost"
    database = "mydb"
    username = "user"
    password = "secret"
}

val pgmq = db.pgmq()

// Create a queue
pgmq.createQueue("orders")

// Send a message (raw JSON)
val msgId = pgmq.send("orders", """{"orderId":"abc123"}""")

// Read messages (makes them invisible for 30 seconds)
val messages = pgmq.read("orders", visibilityTimeout = 30, qty = 1)
println(messages.first().message)

// Delete after processing
pgmq.delete("orders", msgId)
```

---

## PgmqClient API

Obtain via `db.pgmq()`. All methods are `suspend`.

### Queue management

| Method | Description |
|--------|-------------|
| `createQueue(name)` | Create a standard (logged) queue. |
| `createUnloggedQueue(name)` | Create an unlogged queue (faster writes, no crash recovery). |
| `dropQueue(name): Boolean` | Drop a queue. Returns `true` if the queue existed. |
| `listQueues(): List<QueueInfo>` | List all queues with creation metadata. |
| `purgeQueue(name): Long` | Delete all messages from a queue. Returns the count deleted. |

### Metrics

| Method | Description |
|--------|-------------|
| `metrics(queueName): QueueMetrics?` | Per-queue stats (length, age, total). |
| `metricsAll(): List<QueueMetrics>` | Stats for all queues. |

### Sending

| Method | Description |
|--------|-------------|
| `send(queueName, message, delaySeconds = 0): Long` | Enqueue a JSON message. Returns the message ID. |
| `sendBatch(queueName, messages, delaySeconds = 0): List<Long>` | Enqueue multiple messages in one round trip. |

### Reading

| Method | Description |
|--------|-------------|
| `read(queueName, visibilityTimeout, qty = 1): List<PgmqMessage>` | Read up to `qty` messages, making them invisible for `visibilityTimeout` seconds. |
| `readWithPoll(queueName, visibilityTimeout, qty, maxPollSeconds, pollIntervalMs): List<PgmqMessage>` | Like `read`, but blocks server-side up to `maxPollSeconds` waiting for messages. |
| `pop(queueName, qty = 1): List<PgmqMessage>` | Atomically read and delete messages. |

### Delete / Archive

| Method | Description |
|--------|-------------|
| `delete(queueName, msgId): Boolean` | Delete a single message. |
| `deleteBatch(queueName, msgIds): List<Long>` | Delete multiple messages in one round trip. |
| `archive(queueName, msgId): Boolean` | Move a message to the archive table instead of deleting it. |
| `archiveBatch(queueName, msgIds): List<Long>` | Archive multiple messages in one round trip. |

### Visibility timeout

| Method | Description |
|--------|-------------|
| `setVt(queueName, msgId, vtSeconds): PgmqMessage?` | Extend or reset the visibility timeout for a single message. |
| `setVtBatch(queueName, msgIds, vtSeconds): List<PgmqMessage>` | Extend visibility for multiple messages. |

---

## PgmqConsumer

`PgmqConsumer` holds a **dedicated connection** outside the pool and continuously polls the queue
using `pgmq.read_with_poll` (which blocks server-side). Messages are emitted on a `SharedFlow<PgmqMessage>`.

```kotlin
val consumer = db.pgmqConsumer(
    queueName         = "orders",
    visibilityTimeout = 30,
    batchSize         = 10,
    maxPollSeconds    = 5,
)

consumer.messages.collect { msg ->
    processOrder(msg.message)
    consumer.ack(msg.msgId)     // delete after processing
    // or: consumer.archive(msg.msgId)
}
```

### Consumer parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `queueName` | — | Queue to consume. |
| `visibilityTimeout` | `30` | Seconds a message stays invisible while being processed. |
| `batchSize` | `1` | Messages fetched per poll call. |
| `maxPollSeconds` | `5` | Server-side wait time per poll. |
| `pollIntervalMs` | `100` | Polling interval within the server-side wait. |

### Ack / Archive methods on `PgmqConsumer`

These route through the **pool** (not the dedicated connection), so they are fast:

| Method | Description |
|--------|-------------|
| `ack(msgId)` | Delete (acknowledge) a message. |
| `ackBatch(msgIds)` | Delete multiple messages. |
| `archive(msgId)` | Archive a message. |
| `archiveBatch(msgIds)` | Archive multiple messages. |

### Shutdown

```kotlin
consumer.close()  // cancels the polling loop and closes the dedicated connection
```

> **Note:** The consumer holds one dedicated PostgreSQL connection for the lifetime of the consumer.
> Create one consumer per queue; do not share it across queues.

---

## Full example with structured concurrency

```kotlin
val db = Cardio.new { /* ... */ }
val pgmq = db.pgmq()
pgmq.createQueue("orders")

val consumer = db.pgmqConsumer("orders", visibilityTimeout = 60, batchSize = 5)

coroutineScope {
    val job = launch {
        consumer.messages.collect { msg ->
            try {
                println("Processing: ${msg.message}")
                consumer.ack(msg.msgId)
            } catch (e: Exception) {
                // VT will expire and the message will become visible again for retry
            }
        }
    }

    // Send some work
    pgmq.sendBatch("orders", listOf("""{"id":1}""", """{"id":2}"""))

    delay(10.seconds)
    job.cancel()
}

consumer.close()
db.close()
```
