package com.aiassistant

import com.intellij.openapi.diagnostic.Logger

/**
 * Logger for the Code Assistant plugin.
 * Writes to IDE log directory under "plugins/ai-assistant" category.
 * Never logs API keys or code content.
 */
object AppLogger {

    private val LOG = Logger.getInstance("#plugins.ai-assistant")

    fun requestStarted(url: String, tokenCount: Int) {
        LOG.info("Request started: endpoint=${sanitizeUrl(url)}, estimatedTokens=$tokenCount")
    }

    fun requestCompleted(durationMs: Long, tokenCount: Int) {
        LOG.info("Request completed: duration=${durationMs}ms, tokens=$tokenCount")
    }

    fun requestFailed(httpCode: Int, message: String) {
        LOG.warn("Request failed: httpCode=$httpCode, message=${sanitizeMessage(message)}")
    }

    fun apiKeyInvalid() {
        LOG.warn("API key validation failed: user's key rejected by API (401)")
    }

    fun rateLimited() {
        LOG.warn("Rate limited by API (429)")
    }

    fun retryAttempt(attempt: Int, maxRetries: Int) {
        LOG.info("Retry attempt $attempt/$maxRetries")
    }

    fun retryFailed() {
        LOG.warn("All retry attempts exhausted")
    }

    fun streamCancelled() {
        LOG.info("Stream cancelled by user")
    }

    fun settingsSaved(keyLength: Int) {
        LOG.info("API key saved: length=$keyLength")
    }

    fun settingsCleared() {
        LOG.info("API key cleared from settings")
    }

    /**
     * Removes the API endpoint path details—keep only the host.
     */
    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = java.net.URI.create(url)
            uri.host ?: url
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * Truncate long error messages to prevent log bloat.
     */
    private fun sanitizeMessage(message: String): String {
        return if (message.length > 200) message.take(200) + "..." else message
    }
}
