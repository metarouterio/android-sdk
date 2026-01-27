package com.metarouter.analytics

import com.metarouter.analytics.utils.Logger

/**
 * Configuration options for initializing the MetaRouter Analytics SDK.
 *
 * @property writeKey API key for authentication (required, non-empty)
 * @property ingestionHost Backend endpoint URL (required, valid URL without trailing slash)
 * @property flushIntervalSeconds Interval in seconds between automatic flushes (default: 10)
 * @property debug Enable debug logging (default: false)
 * @property maxQueueEvents Maximum enriched events held in queue (default: 2000).
 *   This value also determines the incoming event channel capacity (maxQueueEvents / 2).
 *   Total system capacity = channelCapacity + queueCapacity.
 *   When limits are exceeded, oldest events are dropped.
 *
 * @throws IllegalArgumentException if validation fails
 */
data class InitOptions(
    val writeKey: String,
    val ingestionHost: String,
    val flushIntervalSeconds: Int = 10,
    val debug: Boolean = false,
    val maxQueueEvents: Int = 2000
) {
    init {
        validateWriteKey()
        validateIngestionHost()
        validateFlushInterval()
        validateMaxQueueEvents()
    }

    private fun validateWriteKey() {
        require(writeKey.trim().isNotEmpty()) {
            "MetaRouterAnalyticsClient initialization failed: `writeKey` is required and must be a non-empty string."
        }
    }

    private fun validateIngestionHost() {
        val trimmedHost = ingestionHost.trim()

        require(trimmedHost.isNotEmpty()) {
            "MetaRouterAnalyticsClient initialization failed: `ingestionHost` is required and must be a non-empty string."
        }

        require(isValidUrl(trimmedHost)) {
            "MetaRouterAnalyticsClient initialization failed: `ingestionHost` must be a valid URL."
        }

        require(!trimmedHost.endsWith("/")) {
            "MetaRouterAnalyticsClient initialization failed: `ingestionHost` must be a valid URL and not end in a slash."
        }

        // Security warning for HTTP connections
        if (trimmedHost.startsWith("http://")) {
            Logger.warn(
                "SECURITY WARNING: ingestionHost uses HTTP instead of HTTPS. " +
                "Analytics data may contain PII and should be transmitted securely. " +
                "Consider using HTTPS: ${trimmedHost.replace("http://", "https://")}"
            )
        }
    }

    private fun validateFlushInterval() {
        require(flushIntervalSeconds > 0) {
            "MetaRouterAnalyticsClient initialization failed: `flushIntervalSeconds` must be greater than 0."
        }
    }

    private fun validateMaxQueueEvents() {
        require(maxQueueEvents > 0) {
            "MetaRouterAnalyticsClient initialization failed: `maxQueueEvents` must be greater than 0."
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val parsed = java.net.URL(url)
            // Must be HTTP or HTTPS
            parsed.protocol == "http" || parsed.protocol == "https"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the ingestion host with trailing slash removed (if present).
     * This is a convenience method to ensure consistent URL construction.
     */
    fun getNormalizedIngestionHost(): String = ingestionHost.trimEnd('/')

    /**
     * Get the flush interval in milliseconds for internal use.
     */
    fun getFlushIntervalMillis(): Long = flushIntervalSeconds * 1000L
}
