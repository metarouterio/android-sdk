package com.metarouter.analytics.dispatcher

import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.CircuitState
import com.metarouter.analytics.network.NetworkClient
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.network.parseRetryAfterMs
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates batching and transmission of events from EventQueue to the ingestion endpoint.
 *
 * Responsibilities:
 * - Periodic flush loop based on configured interval
 * - Batch draining with sentAt timestamp injection
 * - HTTP response handling with appropriate retry/backoff strategies
 * - Circuit breaker integration for failure management
 * - Dynamic batch size reduction on 413 responses
 *
 * @param options SDK configuration containing ingestion host and flush interval
 * @param queue Event queue to drain batches from
 * @param networkClient HTTP client for batch transmission
 * @param circuitBreaker Circuit breaker for failure protection
 * @param scope Coroutine scope for async operations
 * @param config Dispatcher-specific configuration
 */
class Dispatcher(
    private val options: InitOptions,
    private val queue: EventQueue,
    private val networkClient: NetworkClient,
    private val circuitBreaker: CircuitBreaker,
    private val scope: CoroutineScope,
    private val config: DispatcherConfig = DispatcherConfig()
) {
    private val maxBatchSize = AtomicInteger(config.initialMaxBatchSize)
    private var flushJob: Job? = null
    private var retryJob: Job? = null
    private val flushMutex = Mutex()
    @Volatile
    private var tracingEnabled = false

    private val json = Json { encodeDefaults = true }

    /**
     * Callback invoked when a fatal configuration error occurs (401, 403, 404).
     * The dispatcher stops and the queue is cleared when this happens.
     */
    var onFatalConfigError: ((Int) -> Unit)? = null

    // ===== Lifecycle =====

    /**
     * Start the periodic flush loop.
     * Flushes events at the interval specified in InitOptions.
     */
    fun start() {
        stop()
        flushJob = scope.launch {
            while (isActive) {
                delay(options.flushIntervalSeconds * 1000L)
                flush()
            }
        }
        Logger.log("Dispatcher started with ${options.flushIntervalSeconds}s flush interval")
    }

    /**
     * Stop the periodic flush loop and any pending retry.
     */
    fun stop() {
        flushJob?.cancel()
        flushJob = null
        retryJob?.cancel()
        retryJob = null
        Logger.log("Dispatcher stopped")
    }

    // ===== Operations =====

    /**
     * Enqueue an event and trigger auto-flush if threshold is reached.
     * This is the primary entry point for new events.
     *
     * @param event The enriched event to enqueue
     */
    suspend fun offer(event: EnrichedEventPayload) {
        queue.enqueue(event)
        Logger.log("Enqueued ${event.type} event (messageId: ${event.messageId})")

        if (queue.size() >= config.autoFlushThreshold) {
            Logger.log("Auto-flush threshold reached (${queue.size()} >= ${config.autoFlushThreshold})")
            flush()
        }
    }

    /**
     * Flush all queued events to the ingestion endpoint.
     * Uses mutex to prevent concurrent flushes.
     */
    suspend fun flush() {
        if (queue.size() == 0) {
            Logger.log("Flush skipped - queue is empty")
            return
        }

        // Prevent concurrent flushes
        if (!flushMutex.tryLock()) {
            Logger.log("Flush skipped - already in progress")
            return
        }

        try {
            processUntilEmpty()
        } finally {
            flushMutex.unlock()
        }
    }

    // ===== Debug =====

    /**
     * Enable or disable tracing headers on requests.
     */
    fun setTracing(enabled: Boolean) {
        tracingEnabled = enabled
        Logger.log("Dispatcher tracing ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get debug information about dispatcher state.
     */
    fun getDebugInfo(): DispatcherDebugInfo {
        return DispatcherDebugInfo(
            isRunning = flushJob?.isActive == true,
            maxBatchSize = maxBatchSize.get(),
            pendingRetry = retryJob?.isActive == true,
            tracingEnabled = tracingEnabled
        )
    }

    // ===== Internal =====

    private suspend fun processUntilEmpty() {
        while (queue.size() > 0) {
            val waitMs = circuitBreaker.beforeRequest()
            if (waitMs > 0) {
                Logger.log("Circuit breaker requires wait: ${waitMs}ms")
                scheduleRetry(waitMs)
                return
            }

            val batch = drainBatch()
            if (batch.isEmpty()) return

            val result = sendBatch(batch)
            if (result == null) {
                // Network error - requeue and schedule retry
                queue.requeueToFront(batch.map { it.first })
                circuitBreaker.onFailure()
                scheduleRetry(1000)
                return
            }

            if (!handleResponse(result, batch.map { it.first })) return
        }
    }

    /**
     * Drain a batch of events and inject sentAt timestamp.
     * Returns pairs of (original event, event with sentAt).
     */
    private fun drainBatch(): List<Pair<EnrichedEventPayload, EnrichedEventPayload>> {
        val events = queue.drain(maxBatchSize.get())
        if (events.isEmpty()) return emptyList()

        val sentAt = Instant.now().toString()
        Logger.log("Drained ${events.size} events for batch (sentAt: $sentAt)")

        return events.map { event ->
            event to event.copy(sentAt = sentAt)
        }
    }

    private suspend fun sendBatch(batch: List<Pair<EnrichedEventPayload, EnrichedEventPayload>>): NetworkResponse? {
        val url = "${options.getNormalizedIngestionHost()}${config.endpointPath}"
        val eventsWithSentAt = batch.map { it.second }
        val payload = BatchPayload(batch = eventsWithSentAt)
        val body = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val headers = mutableMapOf<String, String>()
        if (tracingEnabled) {
            headers["Trace"] = "true"
        }

        Logger.log("Sending batch of ${batch.size} events to $url")

        return try {
            networkClient.postJson(
                url = url,
                body = body,
                timeoutMs = config.timeoutMs,
                additionalHeaders = if (headers.isNotEmpty()) headers else null
            )
        } catch (e: IOException) {
            Logger.error("Network error sending batch: ${e.message}")
            null
        }
    }

    private fun handleResponse(
        response: NetworkResponse,
        batch: List<EnrichedEventPayload>
    ): Boolean {
        Logger.log("Received response: ${response.statusCode}")

        return when (response.statusCode) {
            in 200..299 -> {
                circuitBreaker.onSuccess()
                Logger.log("Batch sent successfully (${batch.size} events)")
                true // continue processing
            }
            in 500..599, 408 -> {
                circuitBreaker.onFailure()
                queue.requeueToFront(batch)
                val retryAfter = parseRetryAfterMs(response.headers) ?: 0
                val waitMs = maxOf(circuitBreaker.beforeRequest(), retryAfter, 1000L)
                Logger.warn("Server error ${response.statusCode} - requeued ${batch.size} events, retry in ${waitMs}ms")
                scheduleRetry(waitMs)
                false
            }
            429 -> {
                circuitBreaker.onFailure()
                queue.requeueToFront(batch)
                val retryAfter = parseRetryAfterMs(response.headers) ?: 0
                val waitMs = maxOf(circuitBreaker.beforeRequest(), retryAfter, 1000L)
                Logger.warn("Rate limited (429) - requeued ${batch.size} events, retry in ${waitMs}ms")
                scheduleRetry(waitMs)
                false
            }
            413 -> {
                circuitBreaker.onNonRetryable()
                val currentSize = maxBatchSize.get()
                if (currentSize > 1) {
                    val newSize = maxBatchSize.updateAndGet { maxOf(1, it / 2) }
                    queue.requeueToFront(batch)
                    Logger.warn("Payload too large (413) - reduced batch size to $newSize, retrying")
                    scheduleRetry(500)
                } else {
                    Logger.warn("Dropping oversized event(s) at batchSize=1 - cannot reduce further")
                }
                false
            }
            401, 403, 404 -> {
                Logger.error("Fatal configuration error: ${response.statusCode} - clearing queue and stopping")
                queue.clear()
                stop()
                onFatalConfigError?.invoke(response.statusCode)
                false
            }
            in 400..499 -> {
                circuitBreaker.onNonRetryable()
                Logger.warn("Client error ${response.statusCode} - dropping batch of ${batch.size} events")
                true // drop batch, continue
            }
            else -> {
                circuitBreaker.onNonRetryable()
                Logger.warn("Unexpected status ${response.statusCode} - continuing")
                true
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            flush()
        }
        Logger.log("Scheduled retry in ${delayMs}ms")
    }
}

/**
 * Batch payload wrapper for JSON serialization.
 */
@kotlinx.serialization.Serializable
private data class BatchPayload(
    val batch: List<EnrichedEventPayload>
)

/**
 * Debug information about dispatcher state.
 */
data class DispatcherDebugInfo(
    val isRunning: Boolean,
    val maxBatchSize: Int,
    val pendingRetry: Boolean,
    val tracingEnabled: Boolean
)
