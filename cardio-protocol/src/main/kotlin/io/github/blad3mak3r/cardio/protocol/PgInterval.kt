package io.github.blad3mak3r.cardio.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.microseconds

/**
 * Represents a PostgreSQL INTERVAL value.
 *
 * PostgreSQL stores intervals as:
 * - microseconds (INT64): time portion
 * - days (INT32): day portion
 * - months (INT32): month portion
 *
 * Note: months and days are kept separate from microseconds because they don't have
 * a fixed conversion (months vary in length, days can be 23-25 hours due to DST).
 */
data class PgInterval(
    val months: Int = 0,
    val days: Int = 0,
    val microseconds: Long = 0
) {
    /**
     * Converts the time portion (microseconds) to a Kotlin Duration.
     * Note: This excludes the months and days components.
     */
    fun toDuration(): Duration = microseconds.microseconds

    /**
     * Converts to a human-readable string similar to PostgreSQL's format.
     */
    override fun toString(): String {
        val parts = mutableListOf<String>()
        
        if (months != 0) {
            val years = months / 12
            val remainingMonths = months % 12
            if (years != 0) parts.add("$years year${if (years != 1) "s" else ""}")
            if (remainingMonths != 0) parts.add("$remainingMonths mon${if (remainingMonths != 1) "s" else ""}")
        }
        
        if (days != 0) {
            parts.add("$days day${if (days != 1) "s" else ""}")
        }
        
        if (microseconds != 0L || parts.isEmpty()) {
            val duration = toDuration()
            parts.add(duration.toString())
        }
        
        return parts.joinToString(" ")
    }

    companion object {
        /**
         * Creates a PgInterval from a Kotlin Duration.
         * The duration is stored in the microseconds component only.
         */
        fun fromDuration(duration: Duration): PgInterval {
            return PgInterval(microseconds = duration.inWholeMicroseconds)
        }

        /**
         * Creates a PgInterval from individual components.
         */
        fun of(months: Int = 0, days: Int = 0, duration: Duration = Duration.ZERO): PgInterval {
            return PgInterval(months, days, duration.inWholeMicroseconds)
        }
    }
}
