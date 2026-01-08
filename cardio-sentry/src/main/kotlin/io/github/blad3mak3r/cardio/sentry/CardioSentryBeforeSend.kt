package io.github.blad3mak3r.cardio.sentry

import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.slf4j.LoggerFactory

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
    
    private val logger = LoggerFactory.getLogger(CardioSentryBeforeSend::class.java)
    
    override fun execute(event: SentryEvent, hint: Hint): SentryEvent? {
        val exception = event.throwable ?: return event
        
        // Check if this is a Cardio exception by checking the class name
        // We use class name checking instead of instanceof to avoid cardio-sentry depending on cardio-postgres
        val className = exception::class.qualifiedName ?: return event
        if (!className.startsWith("io.github.blad3mak3r.cardio.postgres.Cardio")) {
            return event
        }
        
        // Add Cardio-specific tags
        event.setTag("library", "cardio")
        event.setTag("library.version", getCardioVersion())
        
        // Enrich based on exception type using reflection-free approach
        try {
            when {
                className.endsWith("CardioQueryException") -> enrichQueryException(event, exception)
                className.endsWith("CardioExecutionException") -> enrichExecutionException(event, exception)
                className.endsWith("CardioTransactionException") -> enrichTransactionException(event, exception)
                className.endsWith("CardioNullColumnException") -> enrichNullColumnException(event, exception)
                className.endsWith("CardioColumnNotFoundException") -> enrichColumnNotFoundException(event, exception)
            }
        } catch (e: Exception) {
            // Log but don't fail the event sending if enrichment fails
            logger.debug("Failed to enrich Cardio exception: ${e.message}", e)
        }
        
        return event
    }
    
    private fun enrichQueryException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "query")
        
        // Access public properties using reflection
        try {
            val queryMethod = exception::class.java.getMethod("getQuery")
            val query = queryMethod.invoke(exception) as? String
            
            val paramsMethod = exception::class.java.getMethod("getParams")
            val params = paramsMethod.invoke(exception) as? List<*>
            
            if (query != null) {
                event.setExtra("cardio.query", query)
                event.setTag("cardio.query_hash", query.hashCode().toString())
            }
            
            if (params != null) {
                event.setExtra("cardio.params", params)
                event.setExtra("cardio.params_count", params.size)
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract query information: ${e.message}")
        }
    }
    
    private fun enrichExecutionException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "execution")
        
        // Access public properties using reflection
        try {
            val statementMethod = exception::class.java.getMethod("getStatement")
            val statement = statementMethod.invoke(exception) as? String
            
            val paramsMethod = exception::class.java.getMethod("getParams")
            val params = paramsMethod.invoke(exception) as? List<*>
            
            if (statement != null) {
                event.setExtra("cardio.statement", statement)
                event.setTag("cardio.statement_hash", statement.hashCode().toString())
            }
            
            if (params != null) {
                event.setExtra("cardio.params", params)
                event.setExtra("cardio.params_count", params.size)
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract execution information: ${e.message}")
        }
    }
    
    private fun enrichTransactionException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "transaction")
    }
    
    private fun enrichNullColumnException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "null_column")
        
        // Access public properties using reflection
        try {
            val columnNameMethod = exception::class.java.getMethod("getColumnName")
            val columnName = columnNameMethod.invoke(exception) as? String
            
            val availableColumnsMethod = exception::class.java.getMethod("getAvailableColumns")
            val availableColumns = availableColumnsMethod.invoke(exception) as? List<*>
            
            if (columnName != null) {
                event.setExtra("cardio.column_name", columnName)
                event.setTag("cardio.column_name", columnName)
            }
            
            if (availableColumns != null) {
                event.setExtra("cardio.available_columns", availableColumns)
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract null column information: ${e.message}")
        }
    }
    
    private fun enrichColumnNotFoundException(event: SentryEvent, exception: Throwable) {
        event.setTag("cardio.exception_type", "column_not_found")
        
        // Access public properties using reflection
        try {
            val columnNameMethod = exception::class.java.getMethod("getColumnName")
            val columnName = columnNameMethod.invoke(exception) as? String
            
            val availableColumnsMethod = exception::class.java.getMethod("getAvailableColumns")
            val availableColumns = availableColumnsMethod.invoke(exception) as? List<*>
            
            if (columnName != null) {
                event.setExtra("cardio.column_name", columnName)
                event.setTag("cardio.column_name", columnName)
            }
            
            if (availableColumns != null) {
                event.setExtra("cardio.available_columns", availableColumns)
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract column not found information: ${e.message}")
        }
    }
    
    private fun getCardioVersion(): String {
        return try {
            // Try to read version from package
            val pkg = this.javaClass.`package`
            pkg?.implementationVersion ?: "unknown"
        } catch (e: Exception) {
            logger.debug("Failed to get Cardio version: ${e.message}")
            "unknown"
        }
    }
}
