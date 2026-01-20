package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LifecycleState
import com.metarouter.analytics.utils.Logger
import com.metarouter.analytics.utils.toJsonElementMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Main client for the MetaRouter Analytics SDK.
 *
 * Lifecycle:
 * - IDLE → INITIALIZING → READY → RESETTING → IDLE
 * - READY → DISABLED (terminal state for fatal errors)
 *
 * Architecture:
 * - IdentityManager: Manages anonymousId, userId, groupId
 * - DeviceContextProvider: Provides device, app, OS, screen, network context
 * - EventEnrichmentService: Enriches events with identity, context, messageId
 * - EventQueue: Thread-safe FIFO queue with overflow handling
 *
 * Thread Safety:
 * - All public methods are thread-safe
 * - Lifecycle state managed with AtomicReference
 * - Event operations are lock-free after initialization
 * - Initialization and reset use mutex for coordination
 *
 * This is a stub implementation for PR scope: event creation and queueing only.
 * Networking, flushing, circuit breaker will be added in future PRs.
 */
class MetaRouterAnalyticsClient private constructor(
    private val context: Context,
    private val options: InitOptions
) : AnalyticsInterface {

    companion object {
        /**
         * Initialize the MetaRouter Analytics SDK.
         *
         * @param context Android application context
         * @param options Configuration options (writeKey, ingestionHost, etc.)
         * @return Initialized analytics client
         * @throws IllegalArgumentException if options validation fails
         */
        suspend fun initialize(context: Context, options: InitOptions): MetaRouterAnalyticsClient {
            val client = MetaRouterAnalyticsClient(context.applicationContext, options)
            client.initializeInternal()
            return client
        }
    }

    // Lifecycle state
    private val lifecycleState = AtomicReference(LifecycleState.IDLE)
    private val initMutex = Mutex()

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Event processing channel - mimics Swift actor behavior by serializing event enrichment.
    // This provides natural backpressure: if enrichment can't keep up with incoming events,
    // the channel buffer fills up and trySend() will drop events with a clear signal.
    //
    // Similar to Swift's actor mailbox, this ensures:
    // - Events are processed sequentially (one enrichment at a time)
    // - No unbounded coroutine spawning under load
    // - Clear backpressure behavior when overwhelmed
    //
    // Channel capacity is set to half of maxQueueEvents to provide a buffer for incoming
    // events while they're being enriched. This prevents blocking the caller while still
    // maintaining bounded memory usage. Total system capacity = channelSize + queueSize.
    private lateinit var eventChannel: Channel<BaseEvent>

    // Core components
    private lateinit var identityManager: IdentityManager
    private lateinit var contextProvider: DeviceContextProvider
    private lateinit var enrichmentService: EventEnrichmentService
    private lateinit var eventQueue: EventQueue

    /**
     * Internal initialization. Sets up all components.
     */
    private suspend fun initializeInternal() = initMutex.withLock {
        if (lifecycleState.get() != LifecycleState.IDLE) {
            Logger.warn("Attempted to initialize client that is not in IDLE state")
            return
        }

        lifecycleState.set(LifecycleState.INITIALIZING)
        Logger.log("Initializing MetaRouter Analytics SDK...")

        try {
            // Enable debug logging if requested
            if (options.debug) {
                Logger.debugEnabled = true
            }

            // Initialize event channel with capacity based on maxQueueEvents
            // Using half of maxQueueEvents for channel buffer provides a reasonable
            // balance between burst handling and memory usage. The remaining capacity
            // is in the EventQueue for enriched events.
            val channelCapacity = (options.maxQueueEvents / 2).coerceAtLeast(100)
            eventChannel = Channel(capacity = channelCapacity)
            Logger.log("Event channel capacity: $channelCapacity, queue capacity: ${options.maxQueueEvents}")

            // Initialize components
            identityManager = IdentityManager(context)
            contextProvider = DeviceContextProvider(context)
            enrichmentService = EventEnrichmentService(
                identityManager = identityManager,
                contextProvider = contextProvider,
                writeKey = options.writeKey
            )
            eventQueue = EventQueue(maxCapacity = options.maxQueueEvents)

            // Pre-load anonymous ID to ensure it's generated during init
            val anonymousId = identityManager.getAnonymousId()
            Logger.log("SDK initialized with anonymous ID: ${maskId(anonymousId)}")

            // Start event processor coroutine (similar to Swift actor executor)
            startEventProcessor()

            lifecycleState.set(LifecycleState.READY)
            Logger.log("MetaRouter Analytics SDK ready")

        } catch (e: Exception) {
            Logger.error("Failed to initialize SDK: ${e.message}")
            lifecycleState.set(LifecycleState.IDLE)
            throw e
        }
    }

    // ===== Event Tracking Methods =====

    override fun track(event: String, properties: Map<String, Any?>?) {
        enqueueEvent(
            BaseEvent(
                type = EventType.TRACK,
                event = event,
                properties = properties?.toJsonElementMap()
            )
        )
    }

    override fun identify(userId: String, traits: Map<String, Any?>?) {
        if (userId.isBlank()) {
            Logger.warn("Cannot identify with empty userId")
            return
        }

        // Update identity manager then send to channel for enrichment
        scope.launch {
            try {
                identityManager.setUserId(userId)
                enqueueEvent(
                    BaseEvent(
                        type = EventType.IDENTIFY,
                        traits = traits?.toJsonElementMap()
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to queue identify event: ${e.message}")
            }
        }
    }

    override fun group(groupId: String, traits: Map<String, Any?>?) {
        if (groupId.isBlank()) {
            Logger.warn("Cannot group with empty groupId")
            return
        }

        // Update identity manager then send to channel for enrichment
        scope.launch {
            try {
                identityManager.setGroupId(groupId)
                enqueueEvent(
                    BaseEvent(
                        type = EventType.GROUP,
                        traits = traits?.toJsonElementMap()
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to queue group event: ${e.message}")
            }
        }
    }

    override fun screen(name: String, properties: Map<String, Any?>?) {
        enqueueEvent(
            BaseEvent(
                type = EventType.SCREEN,
                event = name,
                properties = properties?.toJsonElementMap()
            )
        )
    }

    override fun page(name: String, properties: Map<String, Any?>?) {
        enqueueEvent(
            BaseEvent(
                type = EventType.PAGE,
                event = name,
                properties = properties?.toJsonElementMap()
            )
        )
    }

    override fun alias(newUserId: String) {
        if (newUserId.isBlank()) {
            Logger.warn("Cannot alias with empty newUserId")
            return
        }

        // Update identity manager then send to channel for enrichment
        scope.launch {
            try {
                identityManager.setUserId(newUserId)
                enqueueEvent(
                    BaseEvent(
                        type = EventType.ALIAS
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to queue alias event: ${e.message}")
            }
        }
    }

    /**
     * Start the event processor coroutine (similar to Swift actor executor).
     * This single consumer processes events sequentially, preventing unbounded concurrency.
     */
    private fun startEventProcessor() {
        scope.launch {
            for (baseEvent in eventChannel) {
                try {
                    val enrichedEvent = enrichmentService.enrichEvent(baseEvent)
                    eventQueue.enqueue(enrichedEvent)
                    Logger.log("Enqueued ${baseEvent.type} event (messageId: ${enrichedEvent.messageId})")
                } catch (e: Exception) {
                    Logger.error("Failed to enqueue event: ${e.message}")
                }
            }
        }
    }

    /**
     * Internal helper to enqueue events.
     * Sends event to processing channel (non-blocking, with backpressure).
     */
    private fun enqueueEvent(baseEvent: BaseEvent) {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot enqueue event - SDK not ready (state: ${lifecycleState.get()})")
            return
        }

        // Try to send to channel - drops if buffer is full (backpressure)
        val result = eventChannel.trySend(baseEvent)
        if (result.isFailure) {
            Logger.warn("Event channel buffer full - dropping ${baseEvent.type} event (system at capacity)")
        }
    }

    // ===== Lifecycle Management =====

    override suspend fun flush() {
        // TODO: Implement flush in networking PR
        Logger.log("Flush called (stub - networking not yet implemented)")
    }

    override suspend fun reset() = initMutex.withLock {
        if (lifecycleState.get() == LifecycleState.IDLE) {
            Logger.log("SDK already in IDLE state")
            return
        }

        Logger.log("Resetting SDK...")
        lifecycleState.set(LifecycleState.RESETTING)

        try {
            // Clear event queue
            eventQueue.clear()

            // Reset identity manager (clears all IDs)
            identityManager.reset()

            lifecycleState.set(LifecycleState.IDLE)
            Logger.log("SDK reset complete")

        } catch (e: Exception) {
            Logger.error("Failed to reset SDK: ${e.message}")
            // Try to recover to READY state
            lifecycleState.set(LifecycleState.READY)
            throw e
        }
    }

    // ===== Debug Methods =====

    override fun enableDebugLogging() {
        Logger.debugEnabled = true
        Logger.log("Debug logging enabled")
    }

    override suspend fun getDebugInfo(): Map<String, Any?> {
        val state = lifecycleState.get()

        return buildMap {
            put("lifecycle", state.toString())
            put("queueLength", if (state == LifecycleState.READY) eventQueue.size() else 0)
            put("ingestionHost", options.ingestionHost)
            put("writeKey", maskWriteKey(options.writeKey))
            put("flushIntervalSeconds", options.flushIntervalSeconds)
            put("maxQueueEvents", options.maxQueueEvents)

            if (state == LifecycleState.READY) {
                put("anonymousId", maskId(identityManager.getAnonymousId()))
                put("userId", identityManager.getUserId()?.let { maskId(it) })
                put("groupId", identityManager.getGroupId()?.let { maskId(it) })
                put("flushInFlight", false) // TODO: Implement in networking PR
                put("circuitState", "CLOSED") // TODO: Implement in networking PR
            } else {
                put("anonymousId", null)
                put("userId", null)
                put("groupId", null)
            }
        }
    }

    // ===== Helper Methods =====

    /**
     * Mask an ID for logging (show first 8 chars).
     */
    private fun maskId(id: String): String {
        return if (id.length <= 8) "***" else "${id.take(8)}***"
    }

    /**
     * Mask write key for logging (show last 4 chars).
     */
    private fun maskWriteKey(writeKey: String): String {
        return if (writeKey.length <= 4) "***" else "***${writeKey.takeLast(4)}"
    }
}
