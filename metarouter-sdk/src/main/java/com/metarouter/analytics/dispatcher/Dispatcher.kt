package com.metarouter.analytics.dispatcher

import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.NetworkClient
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.network.parseRetryAfterMs
import com.metarouter.analytics.queue.EventQueueInterface
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

/**
 * Orchestrates batching and transmission of events from EventQueue to the ingestion endpoint.
 *
 * - Periodic flush loop (idempotent start — won't cancel in-flight flushes)
 * - Two-layer retry backoff: dispatcher-level retry floor + circuit breaker
 * - isFlushing guard prevents concurrent flush/retry overlap
 * - Serialization failures drop the batch (not retried)
 */
class Dispatcher(
    private val options: InitOptions,
    private val queue: EventQueueInterface,
    private val networkClient: NetworkClient,
    private val circuitBreaker: CircuitBreaker,
    private val scope: CoroutineScope,
    private val config: DispatcherConfig = DispatcherConfig()
) {
    private val maxBatchSize = AtomicInteger(config.initialMaxBatchSize)
    @Volatile private var flushJob: Job? = null
    @Volatile private var retryJob: Job? = null
    private val isFlushing = AtomicBoolean(false)
    @Volatile
    private var tracingEnabled = false
    @Volatile
    private var paused = false
    @Volatile
    private var networkPaused = false
    private val consecutiveRetries = AtomicInteger(0)

    private val json = Json { encodeDefaults = true }

    /**
     * Callback invoked when a fatal configuration error occurs (401, 403, 404).
     * The dispatcher stops and the queue is cleared when this happens.
     */
    var onFatalConfigError: ((Int) -> Unit)? = null

    /**
     * Callback invoked after a successful flush cycle completes.
     * Used to trigger overflow disk drain when events spill to disk while online.
     */
    var onFlushComplete: (() -> Unit)? = null

    // ===== Lifecycle =====

    /**
     * Start the periodic flush loop.
     * Idempotent — if already running, this is a no-op (avoids cancelling in-flight requests).
     */
    fun start() {
        if (flushJob != null) return
        flushJob = scope.launch {
            while (isActive) {
                delay(options.flushIntervalSeconds * 1000L)
                try {
                    flush()
                } catch (e: Exception) {
                    Logger.error("Flush loop error: ${e.message}")
                }
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
        consecutiveRetries.set(0)
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
        flushJob = null // Clear so start() doesn't no-op
        start()
    }

    /**
     * Check if the dispatcher is currently paused.
     */
    fun isPaused(): Boolean = paused

    /**
     * Pause HTTP attempts due to offline state. Events continue to enqueue.
     */
    fun pauseForOffline() {
        networkPaused = true
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Resume HTTP attempts after connectivity returns.
     * Resets consecutive retries since offline backoff is irrelevant.
     */
    fun resumeFromOffline() {
        if (!networkPaused) return
        networkPaused = false
        consecutiveRetries.set(0)
    }

    /**
     * Check if HTTP attempts are paused due to offline state.
     */
    fun isNetworkPaused(): Boolean = networkPaused

    // ===== Operations =====

    /**
     * Enqueue an event and trigger auto-flush if threshold is reached.
     */
    suspend fun offer(event: EnrichedEventPayload) {
        Logger.log("Enqueuing event {\"messageId\": \"${event.messageId}\", \"type\": \"${event.type}\"}")
        queue.enqueue(event)
        Logger.log("Event enqueued, queue length: ${queue.size()}")

        if (queue.size() >= config.autoFlushThreshold) {
            flush()
        }
    }

    /**
     * Flush all queued events to the ingestion endpoint.
     * Uses isFlushing guard to prevent concurrent/duplicate flushes.
     */
    suspend fun flush() {
        if (!isFlushing.compareAndSet(false, true)) return
        if (queue.size() == 0) { isFlushing.set(false); return }

        try {
            processUntilEmpty()
            if (!networkPaused) {
                onFlushComplete?.invoke()
            }
        } finally {
            isFlushing.set(false)
        }
    }

    // ===== Debug =====

    fun setTracing(enabled: Boolean) {
        tracingEnabled = enabled
        Logger.log("Dispatcher tracing ${if (enabled) "enabled" else "disabled"}")
    }

    fun getDebugInfo(): DispatcherDebugInfo {
        return DispatcherDebugInfo(
            isRunning = flushJob?.isActive == true,
            maxBatchSize = maxBatchSize.get(),
            pendingRetry = retryJob?.isActive == true,
            tracingEnabled = tracingEnabled,
            networkPaused = networkPaused
        )
    }

    // ===== Direct Send (for disk overflow drain) =====

    /**
     * Send a batch of events directly to the network, bypassing the memory queue.
     * Used by PersistableEventQueue.drainDiskOverflowToNetwork() to flush overflow
     * events from disk without loading them into the memory queue.
     *
     * @return NetworkResponse on HTTP completion, null on encoding or network error
     */
    suspend fun sendBatchDirect(events: List<EnrichedEventPayload>): NetworkResponse? {
        if (events.isEmpty()) return NetworkResponse(200, emptyMap(), null)

        val sentAt = getIso8601Timestamp()
        val batch = events.map { it.copy(sentAt = sentAt) }

        val url = "${options.getNormalizedIngestionHost()}${config.endpointPath}"
        val body: ByteArray
        try {
            val payload = BatchPayload(batch = batch)
            body = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.error("Failed to encode direct batch of ${batch.size} events: ${e.message}")
            return null
        }

        val headers = mutableMapOf<String, String>()
        if (tracingEnabled) {
            headers["Trace"] = "true"
        }

        Logger.log("Making API call to: $url (overflow drain, ${batch.size} events)")

        return try {
            networkClient.postJson(
                url = url,
                body = body,
                timeoutMs = config.timeoutMs,
                additionalHeaders = if (headers.isNotEmpty()) headers else null
            )
        } catch (e: Exception) {
            Logger.warn("Direct batch send failed: ${e.message}")
            null
        }
    }

    // ===== Internal =====

    /**
     * Exponential backoff floor based on consecutive retries, independent of circuit breaker.
     * Ensures retries are never instant even while circuit is closed.
     */
    private fun retryFloorMs(): Long {
        val retries = consecutiveRetries.get()
        if (retries <= 0) return 0L
        val exponent = min(retries - 1, 10)
        return min(
            config.maxRetryDelayMs.toLong(),
            config.baseRetryDelayMs.toLong() * 2.0.pow(exponent).toLong()
        )
    }

    private suspend fun processUntilEmpty() {
        if (networkPaused) {
            if (queue.flushToOfflineStorage()) {
                Logger.log("Flushed queue to offline storage")
            } else {
                Logger.log("Dispatcher skipping flush — device is offline")
            }
            return
        }

        while (queue.size() > 0) {
            if (networkPaused) {
                Logger.log("Dispatcher aborting flush — device went offline")
                return
            }

            val waitMs = circuitBreaker.beforeRequest()
            if (waitMs > 0) {
                Logger.warn("Circuit breaker ${circuitBreaker.getState()}, retrying in ${waitMs}ms (${queue.size()} event(s) pending)")
                scheduleRetry(waitMs)
                return
            }

            val events = queue.drain(maxBatchSize.get())
            if (events.isEmpty()) return

            // Inject sentAt timestamp
            val sentAt = getIso8601Timestamp()
            val batch = events.map { it.copy(sentAt = sentAt) }

            // Serialize — if this fails, drop the batch
            val url = "${options.getNormalizedIngestionHost()}${config.endpointPath}"
            val body: ByteArray
            try {
                val payload = BatchPayload(batch = batch)
                body = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                Logger.error("Failed to encode batch of ${batch.size} events: ${e.message}")
                continue
            }

            val headers = mutableMapOf<String, String>()
            if (tracingEnabled) {
                headers["Trace"] = "true"
            }

            Logger.log("Making API call to: $url")

            val response: NetworkResponse
            try {
                response = networkClient.postJson(
                    url = url,
                    body = body,
                    timeoutMs = config.timeoutMs,
                    additionalHeaders = if (headers.isNotEmpty()) headers else null
                )
            } catch (e: Exception) {
                // Network error — requeue and retry with backoff
                consecutiveRetries.incrementAndGet()
                circuitBreaker.onFailure()
                queue.requeueToFront(events)
                val retryDelay = maxOf(retryFloorMs(), circuitBreaker.beforeRequest())
                Logger.warn("API call failed: ${e.message}, ${queue.size()} event(s) pending retry in ${retryDelay}ms (circuit: ${circuitBreaker.getState()}, retry #${consecutiveRetries.get()})")
                scheduleRetry(retryDelay)
                return
            }

            when (val action = handleResponse(response, events)) {
                is FlushAction.Continue -> {} // continue loop
                is FlushAction.RetryAfter -> { scheduleRetry(action.delayMs); return }
                is FlushAction.Stop -> return
            }
        }
    }

    private fun handleResponse(
        response: NetworkResponse,
        batch: List<EnrichedEventPayload>
    ): FlushAction {
        return when (ResponseCategory.from(response.statusCode)) {
            ResponseCategory.SUCCESS -> {
                consecutiveRetries.set(0)
                circuitBreaker.onSuccess()
                val current = maxBatchSize.get()
                if (current < config.initialMaxBatchSize) {
                    val restored = minOf(current * 2, config.initialMaxBatchSize)
                    maxBatchSize.set(restored)
                }
                Logger.log("API call successful")
                FlushAction.Continue
            }
            ResponseCategory.SERVER_ERROR -> {
                consecutiveRetries.incrementAndGet()
                circuitBreaker.onFailure()
                queue.requeueToFront(batch)
                val retryAfter = parseRetryAfterMs(response.headers) ?: circuitBreaker.beforeRequest()
                val waitMs = maxOf(retryFloorMs(), maxOf(100L, retryAfter))
                Logger.warn("Server error ${response.statusCode}, will retry ${batch.size} event(s) in ${waitMs}ms (circuit: ${circuitBreaker.getState()}, retry #${consecutiveRetries.get()})")
                FlushAction.RetryAfter(waitMs)
            }
            ResponseCategory.RATE_LIMITED -> {
                consecutiveRetries.incrementAndGet()
                circuitBreaker.onFailure()
                queue.requeueToFront(batch)
                val headerDelay = parseRetryAfterMs(response.headers) ?: 0L
                val cbDelay = circuitBreaker.beforeRequest()
                val waitMs = maxOf(retryFloorMs(), maxOf(1000L, maxOf(headerDelay, cbDelay)))
                Logger.warn("Rate limited (429), will retry ${batch.size} event(s) in ${waitMs}ms (circuit: ${circuitBreaker.getState()}, retry #${consecutiveRetries.get()})")
                FlushAction.RetryAfter(waitMs)
            }
            ResponseCategory.PAYLOAD_TOO_LARGE -> {
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
            ResponseCategory.FATAL_CONFIG -> {
                Logger.error("Fatal configuration error: ${response.statusCode} - clearing queue and stopping")
                queue.clear()
                stop()
                onFatalConfigError?.invoke(response.statusCode)
                FlushAction.Stop
            }
            ResponseCategory.CLIENT_ERROR -> {
                circuitBreaker.onNonRetryable()
                Logger.warn("Client error ${response.statusCode} - dropping batch of ${batch.size} events")
                FlushAction.Continue
            }
        }
    }

    /**
     * Schedule a retry after the given delay.
     * Always launches a new coroutine so the retry runs after isFlushing is released.
     */
    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            flush()
        }
    }

    private fun getIso8601Timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.format(Date())
    }
}

@kotlinx.serialization.Serializable
internal data class BatchPayload(
    val batch: List<EnrichedEventPayload>
)

private sealed class FlushAction {
    data object Continue : FlushAction()
    data class RetryAfter(val delayMs: Long) : FlushAction()
    data object Stop : FlushAction()
}

/**
 * Classifies an HTTP status code into a response category.
 * Shared by the main dispatcher flush loop and the overflow drain.
 */
internal enum class ResponseCategory {
    SUCCESS,
    SERVER_ERROR,
    RATE_LIMITED,
    PAYLOAD_TOO_LARGE,
    FATAL_CONFIG,
    CLIENT_ERROR;

    companion object {
        fun from(statusCode: Int): ResponseCategory = when (statusCode) {
            in 200..299 -> SUCCESS
            in 500..599, 408 -> SERVER_ERROR
            429 -> RATE_LIMITED
            413 -> PAYLOAD_TOO_LARGE
            401, 403, 404 -> FATAL_CONFIG
            in 400..499 -> CLIENT_ERROR
            else -> CLIENT_ERROR // Unknown status codes are non-retryable (drop, don't retry)
        }
    }
}

data class DispatcherDebugInfo(
    val isRunning: Boolean,
    val maxBatchSize: Int,
    val pendingRetry: Boolean,
    val tracingEnabled: Boolean,
    val networkPaused: Boolean
)
