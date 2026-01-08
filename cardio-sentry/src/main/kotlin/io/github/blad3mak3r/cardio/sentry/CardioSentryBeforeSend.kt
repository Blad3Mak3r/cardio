package io.github.blad3mak3r.cardio.sentry

import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions

/**
 * Sentry BeforeSend callback that automatically enriches Cardio exceptions with structured data.
 * 
 * This callback is automatically registered when using cardio-sentry and will detect
 * Cardio exceptions (identified by the package name), enriching them with relevant
 * context information before sending to Sentry.
 * 
 * To use this in your application:
 * ```kotlin
 * Sentry.init { options ->
 *     options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
 *     options.beforeSend = CardioSentryBeforeSend()
 * }
 * ```
 * 
 * Or combine with other BeforeSend callbacks:
 * ```kotlin
 * Sentry.init { options ->
 *     options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
 *     val cardioBeforeSend = CardioSentryBeforeSend()
 *     options.beforeSend = SentryOptions.BeforeSendCallback { event, hint ->
 *         // Your custom logic
 *         val processed = customProcessing(event)
 *         // Then let Cardio enrich it
 *         cardioBeforeSend.execute(processed, hint)
 *     }
 * }
 * ```
 */
class CardioSentryBeforeSend : SentryOptions.BeforeSendCallback {
    
    override fun execute(event: SentryEvent, hint: Hint): SentryEvent? {
        val exception = event.throwable ?: return event
        
        // Check if this is a Cardio exception
        if (!isCardioException(exception)) {
            return event
        }
        
        // Add Cardio-specific tags
        event.setTag("library", "cardio")
        event.setTag("library.version", getCardioVersion())
        
        // Enrich based on exception type
        when {
            exception::class.qualifiedName?.contains("CardioQueryException") == true -> {
                enrichQueryException(event, exception)
            }
            exception::class.qualifiedName?.contains("CardioExecutionException") == true -> {
                enrichExecutionException(event, exception)
            }
            exception::class.qualifiedName?.contains("CardioTransactionException") == true -> {
                enrichTransactionException(event, exception)
            }
            exception::class.qualifiedName?.contains("CardioNullColumnException") == true -> {
                enrichNullColumnException(event, exception)
            }
            exception::class.qualifiedName?.contains("CardioColumnNotFoundException") == true -> {
                enrichColumnNotFoundException(event, exception)
            }
        }
        
        return event
    }
    
    private fun isCardioException(exception: Throwable): Boolean {
        val className = exception::class.qualifiedName ?: return false
        return className.startsWith("io.github.blad3mak3r.cardio.postgres.Cardio")
    }
    
    private fun enrichQueryException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "query")
        
        // Try to extract query and params using reflection
        try {
            val queryField = exception::class.java.getDeclaredField("query")
            queryField.isAccessible = true
            val query = queryField.get(exception) as? String
            
            val paramsField = exception::class.java.getDeclaredField("params")
            paramsField.isAccessible = true
            val params = paramsField.get(exception) as? List<*>
            
            if (query != null) {
                event.setExtra("cardio.query", query)
                event.setTag("cardio.query_hash", query.hashCode().toString())
            }
            
            if (params != null) {
                event.setExtra("cardio.params", params)
                event.setExtra("cardio.params_count", params.size)
            }
        } catch (e: Exception) {
            // Reflection failed, ignore
        }
    }
    
    private fun enrichExecutionException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "execution")
        
        // Try to extract statement and params using reflection
        try {
            val statementField = exception::class.java.getDeclaredField("statement")
            statementField.isAccessible = true
            val statement = statementField.get(exception) as? String
            
            val paramsField = exception::class.java.getDeclaredField("params")
            paramsField.isAccessible = true
            val params = paramsField.get(exception) as? List<*>
            
            if (statement != null) {
                event.setExtra("cardio.statement", statement)
                event.setTag("cardio.statement_hash", statement.hashCode().toString())
            }
            
            if (params != null) {
                event.setExtra("cardio.params", params)
                event.setExtra("cardio.params_count", params.size)
            }
        } catch (e: Exception) {
            // Reflection failed, ignore
        }
    }
    
    private fun enrichTransactionException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "transaction")
    }
    
    private fun enrichNullColumnException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "null_column")
        
        // Try to extract column name and available columns using reflection
        try {
            val columnNameField = exception::class.java.getDeclaredField("columnName")
            columnNameField.isAccessible = true
            val columnName = columnNameField.get(exception) as? String
            
            val availableColumnsField = exception::class.java.getDeclaredField("availableColumns")
            availableColumnsField.isAccessible = true
            val availableColumns = availableColumnsField.get(exception) as? List<*>
            
            if (columnName != null) {
                event.setExtra("cardio.column_name", columnName)
                event.setTag("cardio.column_name", columnName)
            }
            
            if (availableColumns != null) {
                event.setExtra("cardio.available_columns", availableColumns)
            }
        } catch (e: Exception) {
            // Reflection failed, ignore
        }
    }
    
    private fun enrichColumnNotFoundException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "column_not_found")
        
        // Try to extract column name and available columns using reflection
        try {
            val columnNameField = exception::class.java.getDeclaredField("columnName")
            columnNameField.isAccessible = true
            val columnName = columnNameField.get(exception) as? String
            
            val availableColumnsField = exception::class.java.getDeclaredField("availableColumns")
            availableColumnsField.isAccessible = true
            val availableColumns = availableColumnsField.get(exception) as? List<*>
            
            if (columnName != null) {
                event.setExtra("cardio.column_name", columnName)
                event.setTag("cardio.column_name", columnName)
            }
            
            if (availableColumns != null) {
                event.setExtra("cardio.available_columns", availableColumns)
            }
        } catch (e: Exception) {
            // Reflection failed, ignore
        }
    }
    
    private fun getCardioVersion(): String {
        return try {
            // Try to read version from package or manifest
            this::class.java.`package`?.implementationVersion ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
