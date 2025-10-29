package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.CodableValue
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LifecycleState
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * - IdentityManager: Manages anonymousId, userId, groupId, advertisingId
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
                properties = properties?.toCodableValueMap()
            )
        )
    }

    override fun identify(userId: String, traits: Map<String, Any?>?) {
        if (userId.isBlank()) {
            Logger.warn("Cannot identify with empty userId")
            return
        }

        // Update identity manager then enqueue event
        scope.launch {
            try {
                identityManager.setUserId(userId)
                val enrichedEvent = enrichmentService.enrichEvent(
                    BaseEvent(
                        type = EventType.IDENTIFY,
                        traits = traits?.toCodableValueMap()
                    )
                )
                eventQueue.enqueue(enrichedEvent)
                Logger.log("Enqueued identify event (messageId: ${enrichedEvent.messageId})")
            } catch (e: Exception) {
                Logger.error("Failed to enqueue identify event: ${e.message}")
            }
        }
    }

    override fun group(groupId: String, traits: Map<String, Any?>?) {
        if (groupId.isBlank()) {
            Logger.warn("Cannot group with empty groupId")
            return
        }

        // Update identity manager then enqueue event
        scope.launch {
            try {
                identityManager.setGroupId(groupId)
                val enrichedEvent = enrichmentService.enrichEvent(
                    BaseEvent(
                        type = EventType.GROUP,
                        traits = traits?.toCodableValueMap()
                    )
                )
                eventQueue.enqueue(enrichedEvent)
                Logger.log("Enqueued group event (messageId: ${enrichedEvent.messageId})")
            } catch (e: Exception) {
                Logger.error("Failed to enqueue group event: ${e.message}")
            }
        }
    }

    override fun screen(name: String, properties: Map<String, Any?>?) {
        enqueueEvent(
            BaseEvent(
                type = EventType.SCREEN,
                event = name,
                properties = properties?.toCodableValueMap()
            )
        )
    }

    override fun page(name: String, properties: Map<String, Any?>?) {
        enqueueEvent(
            BaseEvent(
                type = EventType.PAGE,
                event = name,
                properties = properties?.toCodableValueMap()
            )
        )
    }

    override fun alias(newUserId: String) {
        if (newUserId.isBlank()) {
            Logger.warn("Cannot alias with empty newUserId")
            return
        }

        // Update identity manager then enqueue event
        scope.launch {
            try {
                identityManager.setUserId(newUserId)
                val enrichedEvent = enrichmentService.enrichEvent(
                    BaseEvent(
                        type = EventType.ALIAS
                    )
                )
                eventQueue.enqueue(enrichedEvent)
                Logger.log("Enqueued alias event (messageId: ${enrichedEvent.messageId})")
            } catch (e: Exception) {
                Logger.error("Failed to enqueue alias event: ${e.message}")
            }
        }
    }

    /**
     * Internal helper to enqueue events.
     * Enriches the event and adds to queue.
     */
    private fun enqueueEvent(baseEvent: BaseEvent) {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot enqueue event - SDK not ready (state: ${lifecycleState.get()})")
            return
        }

        // Launch coroutine to enrich and enqueue
        scope.launch {
            try {
                val enrichedEvent = enrichmentService.enrichEvent(baseEvent)
                eventQueue.enqueue(enrichedEvent)
                Logger.log("Enqueued ${baseEvent.type} event (messageId: ${enrichedEvent.messageId})")
            } catch (e: Exception) {
                Logger.error("Failed to enqueue event: ${e.message}")
            }
        }
    }

    // ===== Identity Management =====

    override suspend fun setAdvertisingId(advertisingId: String) {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot set advertising ID - SDK not ready")
            return
        }

        if (advertisingId.isBlank()) {
            Logger.warn("Cannot set empty advertising ID")
            return
        }

        val success = identityManager.setAdvertisingId(advertisingId)
        if (success) {
            // Clear context cache so it regenerates with new advertising ID
            contextProvider.clearCache()
            Logger.log("Advertising ID set and context cache cleared")
        }
    }

    override suspend fun clearAdvertisingId() {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot clear advertising ID - SDK not ready")
            return
        }

        val success = identityManager.clearAdvertisingId()
        if (success) {
            // Clear context cache so it regenerates without advertising ID
            contextProvider.clearCache()
            Logger.log("Advertising ID cleared and context cache cleared")
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

            // Reset identity manager (clears all IDs including advertising ID)
            identityManager.reset()

            // Clear context cache
            contextProvider.clearCache()

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
     * Convert Map<String, Any?> to Map<String, CodableValue>.
     */
    private fun Map<String, Any?>.toCodableValueMap(): Map<String, CodableValue> {
        return mapValues { (_, value) -> CodableValue.fromAny(value) }
    }

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
