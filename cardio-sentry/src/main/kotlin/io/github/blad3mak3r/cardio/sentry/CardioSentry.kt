package io.github.blad3mak3r.cardio.sentry

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import org.slf4j.LoggerFactory

/**
 * Cardio Sentry integration for automatic structured error reporting.
 * 
 * This module automatically captures and structures Cardio exceptions to send to Sentry.
 * It assumes Sentry is already initialized in your application.
 * 
 * No manual configuration is needed - just add the dependency and Cardio exceptions
 * will be automatically enriched with structured data before being sent to Sentry.
 * 
 * Example usage in your application:
 * ```kotlin
 * // Initialize Sentry in your application (not in cardio-sentry)
 * Sentry.init { options ->
 *     options.dsn = "https://your-sentry-dsn@sentry.io/project-id"
 *     options.environment = "production"
 *     options.release = "1.0.0"
 * }
 * 
 * // cardio-sentry will automatically capture and structure Cardio exceptions
 * ```
 */
internal object CardioSentryInternal {
    
    private val logger = LoggerFactory.getLogger(CardioSentryInternal::class.java)
    
    /**
     * Capture an exception with structured context.
     * Internal method used by the library to automatically report exceptions.
     * 
     * @param throwable The exception to capture
     * @param configure Optional configuration for the event
     */
    fun captureException(
        throwable: Throwable,
        configure: (SentryEventBuilder.() -> Unit)? = null
    ) {
        try {
            val event = SentryEvent(throwable).apply {
                level = SentryLevel.ERROR
            }
            
            if (configure != null) {
                val builder = SentryEventBuilder(event)
                builder.configure()
            }
            
            Sentry.captureEvent(event)
        } catch (e: Exception) {
            // If Sentry is not initialized or fails, just log it
            logger.warn("Failed to capture exception to Sentry: ${e.message}")
        }
    }
    
    /**
     * Capture a message with structured context.
     * Internal method used by the library to automatically report messages.
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
        try {
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
        } catch (e: Exception) {
            // If Sentry is not initialized or fails, just log it
            logger.warn("Failed to capture message to Sentry: ${e.message}")
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
