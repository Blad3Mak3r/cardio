package io.github.blad3mak3r.cardio.postgres

/**
 * Base exception for all Cardio-related errors.
 * Provides structured error information to help users diagnose and fix issues.
 */
sealed class CardioException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when a query execution fails.
 * Includes the query statement and parameters for debugging.
 *
 * @param query The SQL query that failed
 * @param params The parameters used in the query
 * @param cause The underlying exception
 */
class CardioQueryException(
    val query: String,
    val params: List<Any?>,
    cause: Throwable
) : CardioException(
    buildString {
        append("Failed to execute query: ${cause.message}\n")
        append("Query: $query\n")
        if (params.isNotEmpty()) {
            append("Parameters: $params\n")
        }
        append("Cause: ${cause::class.simpleName}")
    },
    cause
)

/**
 * Exception thrown when a statement execution (INSERT, UPDATE, DELETE) fails.
 * Includes the statement and parameters for debugging.
 *
 * @param statement The SQL statement that failed
 * @param params The parameters used in the statement
 * @param cause The underlying exception
 */
class CardioExecutionException(
    val statement: String,
    val params: List<Any?>,
    cause: Throwable
) : CardioException(
    buildString {
        append("Failed to execute statement: ${cause.message}\n")
        append("Statement: $statement\n")
        if (params.isNotEmpty()) {
            append("Parameters: $params\n")
        }
        append("Cause: ${cause::class.simpleName}")
    },
    cause
)

/**
 * Exception thrown when a transaction fails.
 * Provides information about the transaction state and the underlying error.
 *
 * @param message Description of the transaction failure
 * @param cause The underlying exception
 */
class CardioTransactionException(
    message: String,
    cause: Throwable? = null
) : CardioException(message, cause)

/**
 * Exception thrown when a required column value is null.
 * Includes the column name and available columns for debugging.
 *
 * @param columnName The name of the column that was null
 * @param availableColumns List of available columns in the row (optional)
 */
class CardioNullColumnException(
    val columnName: String,
    val availableColumns: List<String>? = null
) : CardioException(
    buildString {
        append("Column '$columnName' is null but was accessed as non-nullable.\n")
        append("Use getAsNullable<T>() if the column can be null, or check your query.\n")
        if (!availableColumns.isNullOrEmpty()) {
            append("Available columns: ${availableColumns.joinToString(", ")}")
        }
    }
)

/**
 * Exception thrown when a column is not found in the result set.
 * Includes available columns to help users identify typos.
 *
 * @param columnName The name of the column that was not found
 * @param availableColumns List of available columns in the row
 */
class CardioColumnNotFoundException(
    val columnName: String,
    val availableColumns: List<String>
) : CardioException(
    buildString {
        append("Column '$columnName' not found in result set.\n")
        append("Available columns: ${availableColumns.joinToString(", ")}\n")
        append("Check for typos in the column name or ensure it's included in your SELECT query.")
    }
)
