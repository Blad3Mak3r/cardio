package io.github.blad3mak3r.cardio.sentry

import io.sentry.SentryLevel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine exception handler that automatically captures exceptions to Sentry.
 * 
 * This handler integrates with Kotlin Coroutines to automatically report uncaught
 * exceptions that occur in coroutine contexts.
 * 
 * Example usage:
 * ```kotlin
 * val scope = CoroutineScope(
 *     SupervisorJob() + 
 *     Dispatchers.Default + 
 *     CardioSentryExceptionHandler()
 * )
 * 
 * scope.launch {
 *     // Your code - exceptions will be automatically reported to Sentry
 * }
 * ```
 */
class CardioSentryExceptionHandler(
    private val additionalContext: Map<String, String> = emptyMap()
) : CoroutineExceptionHandler {
    
    private val logger = LoggerFactory.getLogger(CardioSentryExceptionHandler::class.java)
    
    override val key: CoroutineContext.Key<*>
        get() = CoroutineExceptionHandler
    
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        logger.error("Uncaught exception in coroutine context: $context", exception)
        
        CardioSentryInternal.captureException(exception) {
            // Add coroutine context information
            val coroutineName = context[CoroutineName]?.name
            if (coroutineName != null) {
                tag("coroutine.name", coroutineName)
            }
            
            tag("coroutine.context", context.toString())
            
            // Add any additional context provided
            tags(additionalContext)
            
            // Add context information
            context("coroutine", mapOf(
                "context" to context.toString(),
                "name" to (coroutineName ?: "unnamed")
            ))
        }
    }
}
