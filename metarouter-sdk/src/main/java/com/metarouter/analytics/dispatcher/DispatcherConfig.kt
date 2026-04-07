package com.metarouter.analytics.dispatcher

/**
 * Configuration for the event Dispatcher.
 *
 * @property autoFlushThreshold Queue size threshold that triggers an auto-flush
 * @property initialMaxBatchSize Initial maximum events per batch (adjusted dynamically on 413)
 * @property timeoutMs HTTP request timeout in milliseconds
 * @property endpointPath API endpoint path for batch submission
 * @property baseRetryDelayMs Retry floor base delay in milliseconds (exponential backoff from first failure)
 * @property maxRetryDelayMs Retry floor cap in milliseconds before circuit breaker takes over
 */
data class DispatcherConfig(
    val autoFlushThreshold: Int = 20,
    val initialMaxBatchSize: Int = 100,
    val timeoutMs: Int = 8000,
    val endpointPath: String = "/v1/batch",
    val baseRetryDelayMs: Int = 1000,
    val maxRetryDelayMs: Int = 8000
)
