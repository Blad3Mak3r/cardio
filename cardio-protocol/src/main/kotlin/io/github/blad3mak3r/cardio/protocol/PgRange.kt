package io.github.blad3mak3r.cardio.protocol

/**
 * Represents a PostgreSQL range type.
 *
 * A range type stores an interval with optional lower and upper bounds.
 * Each bound can be inclusive or exclusive, and either bound can be unbounded (infinite).
 *
 * @param T the type of the range bounds (e.g., Int, Long)
 * @property lower the lower bound value, or null if unbounded
 * @property upper the upper bound value, or null if unbounded
 * @property lowerInclusive true if the lower bound is inclusive [, false if exclusive (
 * @property upperInclusive true if the upper bound is inclusive ], false if exclusive )
 * @property empty true if this is an empty range
 */
data class PgRange<T : Comparable<T>>(
    val lower: T? = null,
    val upper: T? = null,
    val lowerInclusive: Boolean = true,
    val upperInclusive: Boolean = false,
    val empty: Boolean = false
) {
    init {
        require(!empty || (lower == null && upper == null)) {
            "Empty range cannot have bounds"
        }
    }

    /**
     * Returns true if this range is unbounded (both bounds are null).
     */
    val isUnbounded: Boolean
        get() = !empty && lower == null && upper == null

    /**
     * Returns true if the lower bound is unbounded.
     */
    val isLowerUnbounded: Boolean
        get() = !empty && lower == null

    /**
     * Returns true if the upper bound is unbounded.
     */
    val isUpperUnbounded: Boolean
        get() = !empty && upper == null

    /**
     * Checks if a value is contained in this range.
     */
    fun contains(value: T): Boolean {
        if (empty) return false

        val lowerCheck = when {
            lower == null -> true
            lowerInclusive -> value >= lower
            else -> value > lower
        }

        val upperCheck = when {
            upper == null -> true
            upperInclusive -> value <= upper
            else -> value < upper
        }

        return lowerCheck && upperCheck
    }

    /**
     * Returns a string representation similar to PostgreSQL's format.
     * Examples: [1,10), (,5], [1,), empty
     */
    override fun toString(): String {
        if (empty) return "empty"

        val lowerBracket = if (lowerInclusive) "[" else "("
        val upperBracket = if (upperInclusive) "]" else ")"
        val lowerStr = lower?.toString() ?: ""
        val upperStr = upper?.toString() ?: ""

        return "$lowerBracket$lowerStr,$upperStr$upperBracket"
    }

    companion object {
        /**
         * Creates an empty range.
         */
        fun <T : Comparable<T>> empty(): PgRange<T> = PgRange(empty = true)

        /**
         * Creates a range with both bounds.
         */
        fun <T : Comparable<T>> of(
            lower: T,
            upper: T,
            lowerInclusive: Boolean = true,
            upperInclusive: Boolean = false
        ): PgRange<T> = PgRange(lower, upper, lowerInclusive, upperInclusive)

        /**
         * Creates a range with only a lower bound.
         */
        fun <T : Comparable<T>> atLeast(value: T, inclusive: Boolean = true): PgRange<T> =
            PgRange(lower = value, lowerInclusive = inclusive)

        /**
         * Creates a range with only an upper bound.
         */
        fun <T : Comparable<T>> atMost(value: T, inclusive: Boolean = true): PgRange<T> =
            PgRange(upper = value, upperInclusive = inclusive)

        /**
         * Creates an unbounded range (all values).
         */
        fun <T : Comparable<T>> unbounded(): PgRange<T> = PgRange()
    }
}
