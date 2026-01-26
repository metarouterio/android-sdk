package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.dispatcher.Dispatcher
import com.metarouter.analytics.dispatcher.DispatcherConfig
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.NetworkClient
import com.metarouter.analytics.network.OkHttpNetworkClient
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LifecycleState
import com.metarouter.analytics.utils.Logger
import com.metarouter.analytics.utils.toJsonElementMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class MetaRouterAnalyticsClient private constructor(
    private val context: Context,
    private val options: InitOptions,
    private val injectedIdentityManager: IdentityManager? = null,
    private val injectedContextProvider: DeviceContextProvider? = null,
    private val injectedEnrichmentService: EventEnrichmentService? = null,
    private val injectedEventQueue: EventQueue? = null,
    private val injectedNetworkClient: NetworkClient? = null,
    private val injectedCircuitBreaker: CircuitBreaker? = null,
    private val injectedDispatcher: Dispatcher? = null
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

        /**
         * Initialize with injected dependencies (for testing).
         */
        internal suspend fun initialize(
            context: Context,
            options: InitOptions,
            identityManager: IdentityManager? = null,
            contextProvider: DeviceContextProvider? = null,
            enrichmentService: EventEnrichmentService? = null,
            eventQueue: EventQueue? = null,
            networkClient: NetworkClient? = null,
            circuitBreaker: CircuitBreaker? = null,
            dispatcher: Dispatcher? = null
        ): MetaRouterAnalyticsClient {
            val client = MetaRouterAnalyticsClient(
                context.applicationContext,
                options,
                identityManager,
                contextProvider,
                enrichmentService,
                eventQueue,
                networkClient,
                circuitBreaker,
                dispatcher
            )
            client.initializeInternal()
            return client
        }
    }

    // Lifecycle state
    private val lifecycleState = AtomicReference(LifecycleState.IDLE)

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var eventChannel: Channel<BaseEvent>

    // Core components
    private lateinit var identityManager: IdentityManager
    private lateinit var contextProvider: DeviceContextProvider
    private lateinit var enrichmentService: EventEnrichmentService
    private lateinit var eventQueue: EventQueue
    private lateinit var networkClient: NetworkClient
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var dispatcher: Dispatcher

    /**
     * Internal initialization. Sets up all components.
     */
    private suspend fun initializeInternal() {
        if (!lifecycleState.compareAndSet(LifecycleState.IDLE, LifecycleState.INITIALIZING)) {
            Logger.warn("Attempted to initialize client that is not in IDLE state")
            return
        }
        Logger.log("Initializing MetaRouter Analytics SDK...")

        try {
            // Enable debug logging if requested
            if (options.debug) {
                Logger.debugEnabled = true
            }

            val channelCapacity = (options.maxQueueEvents / 2).coerceAtLeast(100)
            eventChannel = Channel(capacity = channelCapacity)
            Logger.log("Event channel capacity: $channelCapacity, queue capacity: ${options.maxQueueEvents}")

            // Initialize components (use injected or create new)
            identityManager = injectedIdentityManager ?: IdentityManager(context)
            contextProvider = injectedContextProvider ?: DeviceContextProvider(context)
            enrichmentService = injectedEnrichmentService ?: EventEnrichmentService(
                identityManager = identityManager,
                contextProvider = contextProvider,
                writeKey = options.writeKey
            )
            eventQueue = injectedEventQueue ?: EventQueue(maxCapacity = options.maxQueueEvents)

            networkClient = injectedNetworkClient ?: OkHttpNetworkClient()
            circuitBreaker = injectedCircuitBreaker ?: CircuitBreaker()

            dispatcher = injectedDispatcher ?: Dispatcher(
                options = options,
                queue = eventQueue,
                networkClient = networkClient,
                circuitBreaker = circuitBreaker,
                scope = scope,
                config = DispatcherConfig()
            )

            // Pre-load anonymous ID to ensure it's generated during init
            val anonymousId = identityManager.getAnonymousId()
            Logger.log("SDK initialized with anonymous ID: ${maskId(anonymousId)}")

            startEventProcessor()

            dispatcher.start()

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
     * Start the event processor coroutine
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
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot flush - SDK not ready (state: ${lifecycleState.get()})")
            return
        }
        dispatcher.flush()
    }

    /**
     * Called when app goes to background.
     * Flushes pending events and pauses the dispatcher.
     */
    internal suspend fun onBackground() {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.log("onBackground ignored - SDK not ready (state: ${lifecycleState.get()})")
            return
        }
        Logger.log("App backgrounded - flushing and pausing dispatcher")
        flush()
        dispatcher.pause()
    }

    /**
     * Called when app comes to foreground.
     * Flushes pending events and resumes the dispatcher.
     */
    internal fun onForeground() {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.log("onForeground ignored - SDK not ready (state: ${lifecycleState.get()})")
            return
        }
        Logger.log("App foregrounded - flushing and resuming dispatcher")
        scope.launch {
            flush()
        }
        dispatcher.resume()
    }

    override suspend fun reset() {
        if (!lifecycleState.compareAndSet(LifecycleState.READY, LifecycleState.RESETTING)) {
            Logger.warn("Cannot reset - SDK not in READY state (current: ${lifecycleState.get()})")
            return
        }

        Logger.log("Resetting SDK...")

        try {
            // Stop dispatcher first
            dispatcher.stop()

            // Close event channel to stop processor coroutine
            eventChannel.close()

            // Cancel all coroutines in scope
            scope.cancel()

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

                // Dispatcher info
                val dispatcherInfo = dispatcher.getDebugInfo()
                put("dispatcherRunning", dispatcherInfo.isRunning)
                put("dispatcherPaused", dispatcher.isPaused())
                put("maxBatchSize", dispatcherInfo.maxBatchSize)

                // Circuit breaker info
                put("circuitState", circuitBreaker.getState()::class.simpleName)
                put("circuitCooldownMs", circuitBreaker.getRemainingCooldownMs())
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
