package com.metarouter.analytics.dispatcher

/**
 * Configuration for the event Dispatcher.
 *
 * @property autoFlushThreshold Queue size threshold that triggers an auto-flush
 * @property initialMaxBatchSize Initial maximum events per batch (adjusted dynamically on 413)
 * @property timeoutMs HTTP request timeout in milliseconds
 * @property endpointPath API endpoint path for batch submission
 */
data class DispatcherConfig(
    val autoFlushThreshold: Int = 20,
    val initialMaxBatchSize: Int = 100,
    val timeoutMs: Int = 8000,
    val endpointPath: String = "/v1/batch"
)
