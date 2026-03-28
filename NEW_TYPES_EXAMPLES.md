# New Type Support Examples

This document demonstrates how to use the newly added PostgreSQL types in Cardio.

## NUMERIC / DECIMAL

Perfect for financial calculations and precise arithmetic.

```kotlin
import java.math.BigDecimal

// Create a table with NUMERIC column
db.execute("""
    CREATE TABLE products (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,
        price NUMERIC(10, 2) NOT NULL,
        discount DECIMAL(5, 2)
    )
""")

// Insert data with BigDecimal
val price = BigDecimal("99.99")
val discount = BigDecimal("15.50")
db.execute(
    "INSERT INTO products (name, price, discount) VALUES ($1, $2, $3)",
    "Widget", price, discount
)

// Query and retrieve NUMERIC values
val products = db.query("SELECT name, price, discount FROM products") { row ->
    Product(
        name = row.get<String>("name"),
        price = row.get<BigDecimal>("price"),
        discount = row.getOrNull<BigDecimal>("discount")
    )
}

// Use in calculations
val subtotal = products.sumOf { it.price }
val totalDiscount = products.mapNotNull { it.discount }.fold(BigDecimal.ZERO) { acc, d -> acc + d }
```

## TIMESTAMP (without timezone)

Useful when you need to store local date-time values without timezone information.

```kotlin
import kotlinx.datetime.LocalDateTime

// Create a table with TIMESTAMP column
db.execute("""
    CREATE TABLE events (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,
        scheduled_at TIMESTAMP NOT NULL,
        completed_at TIMESTAMP
    )
""")

// Insert data
val scheduledTime = LocalDateTime(2024, 3, 15, 14, 30, 0)
db.execute(
    "INSERT INTO events (name, scheduled_at) VALUES ($1, $2)",
    "Meeting", scheduledTime
)

// Query and retrieve TIMESTAMP values
val events = db.query("SELECT id, name, scheduled_at, completed_at FROM events") { row ->
    Event(
        id = row.get<Int>("id"),
        name = row.get<String>("name"),
        scheduledAt = row.get<LocalDateTime>("scheduled_at"),
        completedAt = row.getOrNull<LocalDateTime>("completed_at")
    )
}

// Update with current local time
val now = kotlinx.datetime.Clock.System.now()
    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
db.execute("UPDATE events SET completed_at = $1 WHERE id = $2", now, eventId)
```

## INTERVAL

Represents PostgreSQL intervals - useful for duration calculations.

```kotlin
import io.github.blad3mak3r.cardio.protocol.PgInterval
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// Create a table with INTERVAL column
db.execute("""
    CREATE TABLE tasks (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,
        estimated_duration INTERVAL NOT NULL,
        actual_duration INTERVAL
    )
""")

// Insert intervals
val estimatedDuration = PgInterval.fromDuration(2.hours + 30.minutes)
db.execute(
    "INSERT INTO tasks (name, estimated_duration) VALUES ($1, $2)",
    "Code Review", estimatedDuration
)

// Insert interval with months and days
val complexInterval = PgInterval(months = 2, days = 15, microseconds = (3.hours).inWholeMicroseconds)
db.execute(
    "INSERT INTO tasks (name, estimated_duration) VALUES ($1, $2)",
    "Long Project", complexInterval
)

// Query intervals
val tasks = db.query("SELECT name, estimated_duration, actual_duration FROM tasks") { row ->
    Task(
        name = row.get<String>("name"),
        estimatedDuration = row.get<PgInterval>("estimated_duration"),
        actualDuration = row.getOrNull<PgInterval>("actual_duration")
    )
}

// Use intervals
for (task in tasks) {
    println("${task.name}: ${task.estimatedDuration}")
    // Output: "Code Review: 2h 30m" (or similar format)
    
    // Access components
    val interval = task.estimatedDuration
    println("Months: ${interval.months}, Days: ${interval.days}")
    
    // Convert time portion to Duration
    val duration = interval.toDuration()
    println("Time portion: $duration")
}

// PostgreSQL interval arithmetic
db.execute("""
    UPDATE tasks 
    SET actual_duration = estimated_duration + INTERVAL '1 hour'
    WHERE id = $1
""", taskId)
```

## Array Support for New Types

All new types support arrays automatically:

```kotlin
// NUMERIC arrays
val prices = listOf(
    BigDecimal("10.99"),
    BigDecimal("25.50"),
    BigDecimal("100.00")
)
db.execute("INSERT INTO price_history (prices) VALUES ($1)", prices)

val result = db.query("SELECT * FROM products WHERE price = ANY($1)", prices) { row ->
    row.get<String>("name")
}

// TIMESTAMP arrays
val timestamps = listOf(
    LocalDateTime(2024, 1, 1, 9, 0, 0),
    LocalDateTime(2024, 1, 2, 9, 0, 0),
    LocalDateTime(2024, 1, 3, 9, 0, 0)
)
db.execute("INSERT INTO schedule (time_slots) VALUES ($1)", timestamps)

// INTERVAL arrays
val durations = listOf(
    PgInterval.fromDuration(1.hours),
    PgInterval.fromDuration(2.hours),
    PgInterval.fromDuration(30.minutes)
)
db.execute("INSERT INTO task_templates (duration_options) VALUES ($1)", durations)
```

## Combined Example: Financial Application

```kotlin
data class Transaction(
    val id: Int,
    val amount: BigDecimal,
    val currency: String,
    val timestamp: LocalDateTime,
    val processingTime: PgInterval
)

// Create schema
db.execute("""
    CREATE TABLE transactions (
        id SERIAL PRIMARY KEY,
        amount NUMERIC(15, 2) NOT NULL,
        currency VARCHAR(3) NOT NULL,
        timestamp TIMESTAMP NOT NULL,
        processing_time INTERVAL
    )
""")

// Insert transaction
val amount = BigDecimal("1234.56")
val timestamp = LocalDateTime(2024, 3, 28, 10, 30, 0)
val processingTime = PgInterval.fromDuration(150.milliseconds)

db.execute(
    """
    INSERT INTO transactions (amount, currency, timestamp, processing_time)
    VALUES ($1, $2, $3, $4)
    """,
    amount, "USD", timestamp, processingTime
)

// Query with filters
val transactions = db.query(
    """
    SELECT id, amount, currency, timestamp, processing_time
    FROM transactions
    WHERE timestamp >= $1 
      AND timestamp < $2
      AND amount >= $3
    ORDER BY timestamp DESC
    """,
    LocalDateTime(2024, 3, 1, 0, 0, 0),
    LocalDateTime(2024, 4, 1, 0, 0, 0),
    BigDecimal("100.00")
) { row ->
    Transaction(
        id = row.get("id"),
        amount = row.get("amount"),
        currency = row.get("currency"),
        timestamp = row.get("timestamp"),
        processingTime = row.getOrNull("processing_time") ?: PgInterval()
    )
}

// Aggregate calculations
val total = db.queryOne(
    "SELECT SUM(amount) as total FROM transactions WHERE currency = $1",
    "USD"
) { row ->
    row.getOrNull<BigDecimal>("total") ?: BigDecimal.ZERO
}
```

## Notes

- **NUMERIC/DECIMAL**: Maps to `java.math.BigDecimal` - perfect for financial calculations where precision matters
- **TIMESTAMP**: Maps to `kotlinx.datetime.LocalDateTime` - use when timezone is not relevant
- **INTERVAL**: Maps to custom `PgInterval` class with separate months, days, and microseconds components
- **INT4RANGE/INT8RANGE/NUMRANGE/TSRANGE/TSTZRANGE/DATERANGE**: Maps to generic `PgRange<T>` - perfect for range queries and interval logic
- **JSON**: Maps to `String` - text-based JSON storage
- **JSONB**: Maps to `String` - binary JSON storage (more efficient, supports indexing)
- All types support arrays via `List<T>` 
- All types use PostgreSQL binary wire format for efficiency
- NULL values are supported via `getOrNull<T>()` method

## JSON and JSONB

PostgreSQL supports both JSON (text-based) and JSONB (binary) types. JSONB is generally preferred for most use cases.

### Differences: JSON vs JSONB

**JSON (text-based):**
- Stores exact text representation
- Preserves whitespace and key order
- Faster to insert (no preprocessing)
- Slower to query
- No indexing support

**JSONB (binary):**
- Stores in decomposed binary format
- Removes whitespace, doesn't preserve key order
- Slower to insert (preprocessing overhead)
- **Much faster to query**
- **Supports indexing (GIN, BTREE)**
- **Recommended for most applications**

```kotlin
// Create tables with JSON columns
db.execute("""
    CREATE TABLE documents (
        id SERIAL PRIMARY KEY,
        metadata JSON NOT NULL,
        data JSONB NOT NULL
    )
""")

// Insert JSON data
val metadata = """{"author": "John Doe", "version": "1.0"}"""
val data = """{"title": "Sample Document", "tags": ["important", "draft"], "count": 42}"""

db.execute(
    "INSERT INTO documents (metadata, data) VALUES ($1, $2)",
    metadata, data
)

// Query JSON data
val documents = db.query("SELECT id, metadata, data FROM documents") { row ->
    Document(
        id = row.get<Int>("id"),
        metadata = row.get<String>("metadata"),
        data = row.get<String>("data")
    )
}

// Parse JSON in Kotlin (use your preferred JSON library)
// Example with kotlinx.serialization:
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class DocumentData(
    val title: String,
    val tags: List<String>,
    val count: Int
)

val jsonString: String = row.get("data")
val parsed = Json.decodeFromString<DocumentData>(jsonString)
println("Title: ${parsed.title}")
println("Tags: ${parsed.tags}")

// JSON operators and functions
// Extract field
val titles = db.query(
    "SELECT data->>'title' as title FROM documents"
) { row -> row.get<String>("title") }

// Filter by JSON field
val importantDocs = db.query(
    """
    SELECT * FROM documents 
    WHERE data->>'tags' LIKE '%important%'
    """
) { row -> row.get<String>("data") }

// JSONB containment operator @>
val matchingDocs = db.query(
    """
    SELECT * FROM documents 
    WHERE data @> $1
    """,
    """{"tags": ["important"]}"""
) { row -> row.get<Int>("id") }

// Update JSON field
db.execute(
    """
    UPDATE documents 
    SET data = jsonb_set(data, '{count}', $1)
    WHERE id = $2
    """,
    "100", documentId
)

// Add new JSON field
db.execute(
    """
    UPDATE documents 
    SET data = data || $1
    WHERE id = $2
    """,
    """{"updated_at": "2024-03-28"}""", documentId
)
```

### JSON Arrays

```kotlin
// Store array of JSON objects
val users = """
[
    {"name": "Alice", "age": 30},
    {"name": "Bob", "age": 25},
    {"name": "Charlie", "age": 35}
]
"""

db.execute("INSERT INTO users_data (users) VALUES ($1)", users)

// Query JSON arrays
db.execute("""
    CREATE TABLE events (
        id SERIAL PRIMARY KEY,
        event_name TEXT,
        participants JSON[]
    )
""")

// Insert array of JSON
val participants = listOf(
    """{"name": "Alice", "role": "speaker"}""",
    """{"name": "Bob", "role": "attendee"}"""
)

db.execute(
    "INSERT INTO events (event_name, participants) VALUES ($1, $2)",
    "Tech Conference", participants
)

// Retrieve array of JSON
val events = db.query("SELECT event_name, participants FROM events") { row ->
    Event(
        name = row.get("event_name"),
        participants = row.get<List<String>>("participants")
    )
}
```

### Advanced JSONB Queries

```kotlin
// Create GIN index for fast JSONB queries
db.execute("CREATE INDEX idx_data_gin ON documents USING GIN (data)")

// Path-based queries
val results = db.query(
    """
    SELECT * FROM documents 
    WHERE data #> '{tags, 0}' = $1
    """,
    "\"important\""
) { row -> row.get<String>("data") }

// Existence operator
val hasField = db.query(
    """
    SELECT * FROM documents 
    WHERE data ? $1
    """,
    "title"
) { row -> row.get<Int>("id") }

// Contains any
val withAnyTag = db.query(
    """
    SELECT * FROM documents 
    WHERE data->'tags' ?| $1
    """,
    arrayOf("important", "urgent")
) { row -> row.get<String>("data") }

// Aggregate JSON
val aggregated = db.queryOne(
    """
    SELECT jsonb_agg(data) as all_data 
    FROM documents
    """
) { row -> row.get<String>("all_data") }

// Build JSON from query
val constructed = db.query(
    """
    SELECT jsonb_build_object(
        'id', id,
        'title', data->>'title',
        'tag_count', jsonb_array_length(data->'tags')
    ) as summary
    FROM documents
    """
) { row -> row.get<String>("summary") }
```

### Using with kotlinx.serialization

```kotlin
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class Product(
    val name: String,
    val price: Double,
    val tags: List<String>,
    val metadata: Map<String, String>? = null
)

// Serialize to JSON string
val product = Product(
    name = "Laptop",
    price = 999.99,
    tags = listOf("electronics", "computer"),
    metadata = mapOf("brand" to "Dell", "model" to "XPS")
)

val jsonString = Json.encodeToString(product)

// Insert
db.execute(
    "INSERT INTO products (data) VALUES ($1)",
    jsonString
)

// Query and deserialize
val products = db.query("SELECT data FROM products") { row ->
    val json = row.get<String>("data")
    Json.decodeFromString<Product>(json)
}

products.forEach { p ->
    println("${p.name}: $${p.price}")
}
```

### Performance Tips

1. **Use JSONB over JSON** for most applications (faster queries, indexing support)
2. **Create GIN indexes** on JSONB columns you query frequently
3. **Extract frequently queried fields** to regular columns for even better performance
4. **Use JSONB operators** (`@>`, `?`, `#>`) instead of string operations
5. **Validate JSON** at application level before inserting

```kotlin
// Hybrid approach: indexed columns + JSONB for flexibility
db.execute("""
    CREATE TABLE products (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,          -- extracted for indexing
        price NUMERIC(10,2) NOT NULL, -- extracted for indexing
        data JSONB NOT NULL,          -- additional flexible data
        CONSTRAINT valid_json CHECK (jsonb_typeof(data) = 'object')
    )
""")

db.execute("CREATE INDEX idx_products_name ON products(name)")
db.execute("CREATE INDEX idx_products_price ON products(price)")
db.execute("CREATE INDEX idx_products_data_gin ON products USING GIN (data)")
```


## Range Types (INT4RANGE, INT8RANGE, NUMRANGE, TSRANGE, TSTZRANGE, DATERANGE)

PostgreSQL range types are perfect for scheduling, bookings, price ranges, time periods, and any interval-based logic.

### Basic Range Operations

```kotlin
import io.github.blad3mak3r.cardio.protocol.PgRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant
import java.math.BigDecimal

// Create a table with range columns
db.execute("""
    CREATE TABLE reservations (
        id SERIAL PRIMARY KEY,
        room_id INT NOT NULL,
        guest_name TEXT NOT NULL,
        duration INT4RANGE NOT NULL,
        price_range INT8RANGE
    )
""")

// Insert ranges with different bound types
val reservation1 = PgRange.of(1, 10)  // [1,10) - default: lower inclusive, upper exclusive
val reservation2 = PgRange.of(10, 20, lowerInclusive = true, upperInclusive = true)  // [10,20]
val priceRange = PgRange.of(100L, 500L)  // [100,500)

db.execute(
    "INSERT INTO reservations (room_id, guest_name, duration, price_range) VALUES ($1, $2, $3, $4)",
    101, "John Doe", reservation1, priceRange
)

// Unbounded ranges
val atLeast100 = PgRange.atLeast(100)  // [100,)
val upTo1000 = PgRange.atMost(1000L, inclusive = false)  // (,1000)
val unbounded = PgRange.unbounded<Int>()  // (,)

db.execute(
    "INSERT INTO reservations (room_id, guest_name, duration) VALUES ($1, $2, $3)",
    102, "Jane Smith", atLeast100
)

// Empty range
val empty = PgRange.empty<Int>()
db.execute(
    "INSERT INTO reservations (room_id, guest_name, duration) VALUES ($1, $2, $3)",
    103, "Cancelled", empty
)

// Query and retrieve ranges
val reservations = db.query(
    "SELECT id, room_id, guest_name, duration, price_range FROM reservations"
) { row ->
    Reservation(
        id = row.get<Int>("id"),
        roomId = row.get<Int>("room_id"),
        guestName = row.get<String>("guest_name"),
        duration = row.get<PgRange<Int>>("duration"),
        priceRange = row.getOrNull<PgRange<Long>>("price_range")
    )
}

// Use range properties
for (reservation in reservations) {
    val range = reservation.duration
    
    println("Guest: ${reservation.guestName}")
    println("Duration: $range")  // e.g., "[1,10)"
    
    if (range.empty) {
        println("  Cancelled/Empty reservation")
    } else {
        println("  Lower bound: ${range.lower} (${if (range.lowerInclusive) "inclusive" else "exclusive"})")
        println("  Upper bound: ${range.upper} (${if (range.upperInclusive) "inclusive" else "exclusive"})")
        
        // Check if value is in range
        if (range.contains(5)) {
            println("  Day 5 is within this reservation")
        }
    }
}

// PostgreSQL range operators in queries
// Find overlapping reservations
val checkRange = PgRange.of(5, 15)
val overlapping = db.query(
    """
    SELECT * FROM reservations 
    WHERE room_id = $1 
      AND duration && $2  -- overlaps operator
    """,
    101, checkRange
) { row ->
    row.get<String>("guest_name")
}

// Find reservations that contain a specific day
val containingDay = db.query(
    """
    SELECT * FROM reservations 
    WHERE duration @> $1  -- contains element operator
    """,
    7
) { row ->
    row.get<String>("guest_name")
}

// Find ranges within another range
val withinRange = db.query(
    """
    SELECT * FROM reservations 
    WHERE duration <@ $1  -- contained by operator
    """,
    PgRange.of(1, 100)
) { row ->
    row.get<String>("guest_name")
}

// Range boundaries
val withBoundaries = db.query(
    """
    SELECT 
        guest_name,
        duration,
        lower(duration) as start_day,
        upper(duration) as end_day,
        lower_inc(duration) as start_inclusive,
        upper_inc(duration) as end_inclusive,
        isempty(duration) as is_empty
    FROM reservations
    """,
    
) { row ->
    ReservationDetails(
        guestName = row.get("guest_name"),
        duration = row.get("duration"),
        startDay = row.getOrNull<Int>("start_day"),
        endDay = row.getOrNull<Int>("end_day"),
        startInclusive = row.getOrNull<Boolean>("start_inclusive") ?: false,
        endInclusive = row.getOrNull<Boolean>("end_inclusive") ?: false,
        isEmpty = row.get<Boolean>("is_empty")
    )
}
```

### Range Type Use Cases

**Scheduling & Bookings:**
```kotlin
// Check for time slot conflicts
data class TimeSlot(val start: Int, val end: Int)

fun checkAvailability(roomId: Int, timeSlot: TimeSlot): Boolean {
    val range = PgRange.of(timeSlot.start, timeSlot.end)
    
    val conflicts = db.query(
        """
        SELECT COUNT(*) as count 
        FROM reservations 
        WHERE room_id = $1 AND duration && $2
        """,
        roomId, range
    ) { row -> row.get<Long>("count") }.first()
    
    return conflicts == 0L
}
```

**Price Ranges:**
```kotlin
// Find products within a price range
val minPrice = 100L
val maxPrice = 500L
val priceFilter = PgRange.of(minPrice, maxPrice, upperInclusive = true)

val products = db.query(
    """
    SELECT name, price 
    FROM products 
    WHERE int8range(price, price, '[]') && $1
    """,
    priceFilter
) { row ->
    Product(row.get("name"), row.get("price"))
}
```

**Version Ranges:**
```kotlin
// Software version compatibility
val compatibleVersions = PgRange.of(10, 20)  // versions 10-19 compatible

db.execute(
    """
    INSERT INTO software_compatibility (software_id, compatible_versions)
    VALUES ($1, $2)
    """,
    "app-xyz", compatibleVersions
)

// Check if version is compatible
val version = 15
val isCompatible = db.queryOne(
    """
    SELECT compatible_versions @> $1 as is_compatible
    FROM software_compatibility
    WHERE software_id = $2
    """,
    version, "app-xyz"
) { row -> row.get<Boolean>("is_compatible") }
```

### NUMRANGE - Numeric Range Type

Perfect for price ranges, budgets, and any numeric intervals.

```kotlin
// Create products table with price range
db.execute("""
    CREATE TABLE products (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,
        price_range NUMRANGE NOT NULL
    )
""")

// Insert products with price ranges
val budget = PgRange.of(BigDecimal("100.00"), BigDecimal("500.00"))
val luxury = PgRange.of(BigDecimal("1000.00"), BigDecimal("5000.00"))
val premium = PgRange.atLeast(BigDecimal("10000.00"))  // 10k and up

db.execute("INSERT INTO products (name, price_range) VALUES ($1, $2)", "Budget Laptop", budget)
db.execute("INSERT INTO products (name, price_range) VALUES ($1, $2)", "Luxury Watch", luxury)
db.execute("INSERT INTO products (name, price_range) VALUES ($1, $2)", "Premium Car", premium)

// Find products within budget
val myBudget = BigDecimal("250.00")
val affordable = db.query(
    "SELECT name, price_range FROM products WHERE price_range @> $1",
    myBudget
) { row ->
    Product(row.get("name"), row.get("price_range"))
}

// Find products overlapping with a price range
val searchRange = PgRange.of(BigDecimal("200.00"), BigDecimal("1500.00"))
val results = db.query(
    "SELECT name FROM products WHERE price_range && $1",
    searchRange
) { row -> row.get<String>("name") }
```

### TSRANGE - Timestamp Range (without timezone)

Great for local time-based scheduling and events.

```kotlin
// Create events table with local time ranges
db.execute("""
    CREATE TABLE local_events (
        id SERIAL PRIMARY KEY,
        event_name TEXT NOT NULL,
        time_slot TSRANGE NOT NULL
    )
""")

// Schedule events
val morning = PgRange.of(
    LocalDateTime(2024, 3, 28, 9, 0, 0),
    LocalDateTime(2024, 3, 28, 12, 0, 0)
)

val afternoon = PgRange.of(
    LocalDateTime(2024, 3, 28, 14, 0, 0),
    LocalDateTime(2024, 3, 28, 17, 0, 0),
    upperInclusive = true  // inclusive end time
)

db.execute("INSERT INTO local_events (event_name, time_slot) VALUES ($1, $2)", "Morning Workshop", morning)
db.execute("INSERT INTO local_events (event_name, time_slot) VALUES ($1, $2)", "Afternoon Meeting", afternoon)

// Check for conflicts
val proposedTime = PgRange.of(
    LocalDateTime(2024, 3, 28, 11, 0, 0),
    LocalDateTime(2024, 3, 28, 13, 0, 0)
)

val conflicts = db.query(
    "SELECT event_name FROM local_events WHERE time_slot && $1",
    proposedTime
) { row -> row.get<String>("event_name") }

if (conflicts.isEmpty()) {
    println("Time slot is available!")
} else {
    println("Conflicts with: ${conflicts.joinToString()}")
}
```

### TSTZRANGE - Timestamp with Timezone Range

Perfect for global scheduling across timezones.

```kotlin
// Create global events table
db.execute("""
    CREATE TABLE global_events (
        id SERIAL PRIMARY KEY,
        event_name TEXT NOT NULL,
        utc_time_slot TSTZRANGE NOT NULL
    )
""")

// Schedule global events in UTC
val webinar = PgRange.of(
    Instant.fromEpochSeconds(1711612800),  // 2024-03-28 08:00:00 UTC
    Instant.fromEpochSeconds(1711616400)   // 2024-03-28 09:00:00 UTC
)

db.execute("INSERT INTO global_events (event_name, utc_time_slot) VALUES ($1, $2)", "Global Webinar", webinar)

// Find events happening now
val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
val currentEvents = db.query(
    "SELECT event_name FROM global_events WHERE utc_time_slot @> $1",
    now
) { row -> row.get<String>("event_name") }

// Find events in the next 24 hours
val next24h = PgRange.of(
    now,
    Instant.fromEpochSeconds(now.epochSeconds + 86400)
)

val upcomingEvents = db.query(
    "SELECT event_name, utc_time_slot FROM global_events WHERE utc_time_slot && $1",
    next24h
) { row ->
    Event(row.get("event_name"), row.get("utc_time_slot"))
}
```

### DATERANGE - Date Range Type

Ideal for vacation periods, booking dates, validity periods, etc.

```kotlin
// Create bookings table with date ranges
db.execute("""
    CREATE TABLE hotel_bookings (
        id SERIAL PRIMARY KEY,
        room_number INT NOT NULL,
        guest_name TEXT NOT NULL,
        stay_period DATERANGE NOT NULL
    )
""")

// Book a room
val checkIn = LocalDate(2024, 7, 1)
val checkOut = LocalDate(2024, 7, 7)
val vacation = PgRange.of(checkIn, checkOut)  // [2024-07-01, 2024-07-07)

db.execute(
    "INSERT INTO hotel_bookings (room_number, guest_name, stay_period) VALUES ($1, $2, $3)",
    101, "John Doe", vacation
)

// Check room availability
fun isRoomAvailable(roomNumber: Int, period: PgRange<LocalDate>): Boolean {
    val overlapping = db.query(
        """
        SELECT COUNT(*) as count 
        FROM hotel_bookings 
        WHERE room_number = $1 AND stay_period && $2
        """,
        roomNumber, period
    ) { row -> row.get<Long>("count") }.first()
    
    return overlapping == 0L
}

// Find available rooms for a period
val desiredPeriod = PgRange.of(LocalDate(2024, 7, 5), LocalDate(2024, 7, 10))
val availableRooms = db.query(
    """
    SELECT DISTINCT room_number 
    FROM hotel_bookings 
    WHERE room_number NOT IN (
        SELECT room_number 
        FROM hotel_bookings 
        WHERE stay_period && $1
    )
    """,
    desiredPeriod
) { row -> row.get<Int>("room_number") }

// Extend a booking
val extendedStay = PgRange.of(checkIn, LocalDate(2024, 7, 14))
db.execute(
    "UPDATE hotel_bookings SET stay_period = $1 WHERE id = $2",
    extendedStay, bookingId
)

// Find all bookings for a specific date
val targetDate = LocalDate(2024, 7, 4)
val activeBookings = db.query(
    "SELECT guest_name, stay_period FROM hotel_bookings WHERE stay_period @> $1",
    targetDate
) { row ->
    Booking(row.get("guest_name"), row.get("stay_period"))
}
```

### Combined Example: Conference Room Scheduling

```kotlin
data class RoomBooking(
    val id: Int,
    val roomName: String,
    val eventName: String,
    val timeSlot: PgRange<LocalDateTime>,
    val attendeeCount: PgRange<Int>
)

// Create comprehensive booking system
db.execute("""
    CREATE TABLE conference_rooms (
        id SERIAL PRIMARY KEY,
        room_name TEXT NOT NULL,
        event_name TEXT NOT NULL,
        time_slot TSRANGE NOT NULL,
        attendee_count INT4RANGE,
        budget NUMRANGE,
        EXCLUDE USING GIST (room_name WITH =, time_slot WITH &&)
    )
""")

// Book a room with all range types
val booking = RoomBooking(
    id = 0,
    roomName = "Main Hall",
    eventName = "Tech Conference",
    timeSlot = PgRange.of(
        LocalDateTime(2024, 9, 15, 9, 0, 0),
        LocalDateTime(2024, 9, 15, 17, 0, 0)
    ),
    attendeeCount = PgRange.of(50, 200)
)

val budget = PgRange.of(BigDecimal("5000"), BigDecimal("10000"))

db.execute(
    """
    INSERT INTO conference_rooms 
    (room_name, event_name, time_slot, attendee_count, budget)
    VALUES ($1, $2, $3, $4, $5)
    """,
    booking.roomName, booking.eventName, booking.timeSlot, 
    booking.attendeeCount, budget
)

// Find suitable rooms
val requiredTime = PgRange.of(
    LocalDateTime(2024, 9, 15, 14, 0, 0),
    LocalDateTime(2024, 9, 15, 16, 0, 0)
)
val expectedAttendees = 75
val maxBudget = BigDecimal("8000")

val suitableRooms = db.query(
    """
    SELECT * FROM conference_rooms
    WHERE attendee_count @> $1
      AND budget @> $2
      AND NOT (time_slot && $3)
    """,
    expectedAttendees, maxBudget, requiredTime
) { row ->
    RoomBooking(
        id = row.get("id"),
        roomName = row.get("room_name"),
        eventName = row.get("event_name"),
        timeSlot = row.get("time_slot"),
        attendeeCount = row.get("attendee_count")
    )
}
```
