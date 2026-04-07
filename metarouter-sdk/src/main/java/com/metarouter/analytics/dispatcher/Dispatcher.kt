package com.metarouter.analytics.dispatcher

import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.network.CircuitBreaker
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
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
    private val isFlushing = AtomicBoolean(false)
    @Volatile
    private var tracingEnabled = false
    @Volatile
    private var paused = false

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
    }

    /**
     * Stop the periodic flush loop and any pending retry.
     */
    fun stop() {
        flushJob?.cancel()
        flushJob = null
        retryJob?.cancel()
        retryJob = null
        isFlushing.set(false)
        paused = false
    }

    /**
     * Pause the dispatcher - stops flush loop and cancels pending retries.
     */
    fun pause() {
        flushJob?.cancel()
        flushJob = null
        retryJob?.cancel()
        retryJob = null
        isFlushing.set(false)
        paused = true
    }

    /**
     * Resume the dispatcher - restarts flush loop if paused.
     */
    fun resume() {
        if (!paused) return
        paused = false
        start()
    }

    /**
     * Check if the dispatcher is currently paused.
     */
    fun isPaused(): Boolean = paused

    // ===== Operations =====

    /**
     * Enqueue an event and trigger auto-flush if threshold is reached.
     * This is the primary entry point for new events.
     *
     * @param event The enriched event to enqueue
     */
    suspend fun offer(event: EnrichedEventPayload) {
        Logger.log("Enqueuing event {\"messageId\": \"${event.messageId}\", \"type\": \"${event.type}\"}")
        queue.enqueue(event)
        Logger.log("Event enqueued, queue length: ${queue.size()}")

        if (queue.size() >= config.autoFlushThreshold) {
            Logger.log("Auto-flush threshold reached (${queue.size()} >= ${config.autoFlushThreshold})")
            flush()
        }
    }

    /**
     * Flush all queued events to the ingestion endpoint.
     * Uses isFlushing guard to prevent concurrent/duplicate flushes (matches iOS).
     */
    suspend fun flush() {
        if (queue.size() == 0) return
        if (!isFlushing.compareAndSet(false, true)) return

        try {
            processUntilEmpty()
        } finally {
            isFlushing.set(false)
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
                scheduleRetry(waitMs)
                return
            }

            val batch = drainBatch()
            if (batch.isEmpty()) return

            val result = sendBatch(batch)
            if (result == null) {
                // Network error - requeue and retry using circuit breaker delay
                queue.requeueToFront(batch.map { it.first })
                circuitBreaker.onFailure()
                scheduleRetry(circuitBreaker.beforeRequest())
                return
            }

            when (val action = handleResponse(result, batch.map { it.first })) {
                is FlushAction.Continue -> {} // continue loop
                is FlushAction.RetryAfter -> { scheduleRetry(action.delayMs); return }
                is FlushAction.Stop -> return
            }
        }
    }

    /**
     * Drain a batch of events and inject sentAt timestamp.
     * Returns pairs of (original event, event with sentAt).
     */
    private fun drainBatch(): List<Pair<EnrichedEventPayload, EnrichedEventPayload>> {
        val events = queue.drain(maxBatchSize.get())
        if (events.isEmpty()) return emptyList()

        val sentAt = getIso8601Timestamp()

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

        Logger.log("Making API call to: $url")

        return try {
            networkClient.postJson(
                url = url,
                body = body,
                timeoutMs = config.timeoutMs,
                additionalHeaders = if (headers.isNotEmpty()) headers else null
            )
        } catch (e: IOException) {
            Logger.error("API call failed: ${e.message}, ${batch.size} event(s) pending retry")
            null
        }
    }

    /**
     * Handle HTTP response and determine next action.
     */
    private fun handleResponse(
        response: NetworkResponse,
        batch: List<EnrichedEventPayload>
    ): FlushAction {
        return when (response.statusCode) {
            in 200..299 -> {
                circuitBreaker.onSuccess()
                val current = maxBatchSize.get()
                if (current < config.initialMaxBatchSize) {
                    val restored = minOf(current * 2, config.initialMaxBatchSize)
                    maxBatchSize.set(restored)
                }
                Logger.log("API call successful")
                FlushAction.Continue
            }
            in 500..599, 408 -> {
                circuitBreaker.onFailure()
                queue.requeueToFront(batch)
                val retryAfter = parseRetryAfterMs(response.headers) ?: 0
                val cbDelay = circuitBreaker.beforeRequest()
                val waitMs = maxOf(cbDelay, retryAfter)
                Logger.warn("Server error ${response.statusCode}, will retry ${batch.size} event(s) in ${waitMs}ms")
                FlushAction.RetryAfter(waitMs)
            }
            429 -> {
                circuitBreaker.onFailure()
                queue.requeueToFront(batch)
                val retryAfter = parseRetryAfterMs(response.headers) ?: 0
                val cbDelay = circuitBreaker.beforeRequest()
                val waitMs = maxOf(1000L, maxOf(retryAfter, cbDelay))
                Logger.warn("Rate limited (429), will retry ${batch.size} event(s) in ${waitMs}ms")
                FlushAction.RetryAfter(waitMs)
            }
            413 -> {
                circuitBreaker.onNonRetryable()
                val currentSize = maxBatchSize.get()
                if (currentSize > 1) {
                    val newSize = maxOf(1, currentSize / 2)
                    maxBatchSize.set(newSize)
                    queue.requeueToFront(batch)
                    Logger.warn("Payload too large (413) - reduced batch size to $newSize, retrying")
                    FlushAction.RetryAfter(0)
                } else {
                    Logger.warn("Dropping oversized event(s) at batchSize=1 - cannot reduce further")
                    FlushAction.Stop
                }
            }
            401, 403, 404 -> {
                Logger.error("Fatal configuration error: ${response.statusCode} - clearing queue and stopping")
                queue.clear()
                stop()
                onFatalConfigError?.invoke(response.statusCode)
                FlushAction.Stop
            }
            in 400..499 -> {
                circuitBreaker.onNonRetryable()
                Logger.warn("Client error ${response.statusCode} - dropping batch of ${batch.size} events")
                FlushAction.Continue
            }
            else -> {
                circuitBreaker.onNonRetryable()
                Logger.warn("Unexpected status ${response.statusCode} - continuing")
                FlushAction.Continue
            }
        }
    }

    /**
     * Cancels any previously scheduled retry to prevent duplicates.
     */
    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            flush()
        }
    }

    /**
     * Generate an ISO 8601 timestamp.
     * Creates a new SimpleDateFormat instance each time for thread safety.
     */
    private fun getIso8601Timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.format(Date())
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
 * Internal action returned by handleResponse to control flush loop behavior.
 */
private sealed class FlushAction {
    /** Continue processing the next batch in the queue. */
    data object Continue : FlushAction()
    /** Schedule an async retry after [delayMs] and exit the flush loop. */
    data class RetryAfter(val delayMs: Long) : FlushAction()
    /** Stop processing entirely (fatal error or unrecoverable). */
    data object Stop : FlushAction()
}

/**
 * Debug information about dispatcher state.
 */
data class DispatcherDebugInfo(
    val isRunning: Boolean,
    val maxBatchSize: Int,
    val pendingRetry: Boolean,
    val tracingEnabled: Boolean
)
