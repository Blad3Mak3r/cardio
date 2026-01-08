package io.github.blad3mak3r.cardio.sentry

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import org.slf4j.LoggerFactory

/**
 * Cardio Sentry integration for detailed error reporting.
 * 
 * This class provides a simple way to initialize Sentry and capture errors
 * with structured information.
 * 
 * Example usage:
 * ```kotlin
 * // Initialize Sentry
 * CardioSentry.init {
 *     dsn = "https://your-sentry-dsn"
 *     environment = "production"
 *     release = "1.0.0"
 * }
 * 
 * // Capture an exception
 * try {
 *     // Your code
 * } catch (e: Exception) {
 *     CardioSentry.captureException(e) {
 *         tag("component", "database")
 *         context("query", mapOf("sql" to "SELECT * FROM users"))
 *     }
 * }
 * ```
 */
object CardioSentry {
    
    private val logger = LoggerFactory.getLogger(CardioSentry::class.java)
    
    /**
     * Initialize Sentry with the provided configuration.
     * 
     * @param configure Configuration builder for Sentry options
     */
    fun init(configure: SentryOptions.() -> Unit) {
        try {
            Sentry.init { options ->
                options.configure()
                logger.info("Cardio Sentry initialized with DSN: ${options.dsn}")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Cardio Sentry", e)
            throw e
        }
    }
    
    /**
     * Capture an exception with optional additional context.
     * 
     * @param throwable The exception to capture
     * @param configure Optional configuration for the event
     */
    fun captureException(
        throwable: Throwable,
        configure: (SentryEventBuilder.() -> Unit)? = null
    ) {
        val event = SentryEvent(throwable).apply {
            level = SentryLevel.ERROR
        }
        
        if (configure != null) {
            val builder = SentryEventBuilder(event)
            builder.configure()
        }
        
        Sentry.captureEvent(event)
    }
    
    /**
     * Capture a message with optional level and additional context.
     * 
     * @param message The message to capture
     * @param level The severity level (default: INFO)
     * @param configure Optional configuration for the event
     */
    fun captureMessage(
        message: String,
        level: SentryLevel = SentryLevel.INFO,
        configure: (SentryEventBuilder.() -> Unit)? = null
    ) {
        val event = SentryEvent().apply {
            this.message = Message().apply {
                this.message = message
            }
            this.level = level
        }
        
        if (configure != null) {
            val builder = SentryEventBuilder(event)
            builder.configure()
        }
        
        Sentry.captureEvent(event)
    }
    
    /**
     * Close the Sentry client and flush any pending events.
     * Should be called when shutting down the application.
     * 
     * @param timeoutMillis Maximum time to wait for events to be sent (default: 2000ms)
     */
    fun close(timeoutMillis: Long = 2000L) {
        try {
            Sentry.close()
            logger.info("Cardio Sentry closed successfully")
        } catch (e: Exception) {
            logger.error("Error closing Cardio Sentry", e)
        }
    }
}

/**
 * Builder class for configuring Sentry events with additional context.
 */
class SentryEventBuilder(private val event: SentryEvent) {
    
    /**
     * Add a tag to the event.
     * Tags are key-value pairs used for filtering and searching events in Sentry.
     * 
     * @param key The tag key
     * @param value The tag value
     */
    fun tag(key: String, value: String) {
        event.setTag(key, value)
    }
    
    /**
     * Add multiple tags to the event.
     * 
     * @param tags Map of tag keys to values
     */
    fun tags(tags: Map<String, String>) {
        tags.forEach { (key, value) ->
            event.setTag(key, value)
        }
    }
    
    /**
     * Add a context to the event.
     * Contexts are structured data attached to the event for additional information.
     * 
     * @param key The context key
     * @param value The context data as a map
     */
    fun context(key: String, value: Map<String, Any>) {
        event.setExtra(key, value)
    }
    
    /**
     * Add a simple key-value extra to the event.
     * 
     * @param key The extra key
     * @param value The extra value
     */
    fun extra(key: String, value: Any) {
        event.setExtra(key, value)
    }
    
    /**
     * Add multiple extras to the event.
     * 
     * @param extras Map of extra keys to values
     */
    fun extras(extras: Map<String, Any>) {
        extras.forEach { (key, value) ->
            event.setExtra(key, value)
        }
    }
    
    /**
     * Set the fingerprint for grouping similar events.
     * Events with the same fingerprint are grouped together in Sentry.
     * 
     * @param fingerprint List of strings that identify the event
     */
    fun fingerprint(vararg fingerprint: String) {
        event.fingerprints = fingerprint.toList()
    }
}
