package com.metarouter.analytics

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.dispatcher.Dispatcher
import com.metarouter.analytics.dispatcher.DispatcherConfig
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.network.AndroidNetworkMonitor
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.DebouncedNetworkHandler
import com.metarouter.analytics.network.NetworkClient
import com.metarouter.analytics.network.NetworkMonitor
import com.metarouter.analytics.network.OkHttpNetworkClient
import com.metarouter.analytics.queue.EventQueueInterface
import com.metarouter.analytics.queue.PersistableEventQueue
import com.metarouter.analytics.storage.EventDiskStore
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
    private val injectedEventQueue: EventQueueInterface? = null,
    private val injectedNetworkClient: NetworkClient? = null,
    private val injectedCircuitBreaker: CircuitBreaker? = null,
    private val injectedDispatcher: Dispatcher? = null,
    private val injectedDiskStore: EventDiskStore? = null,
    private val injectedNetworkMonitor: NetworkMonitor? = null
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
            eventQueue: EventQueueInterface? = null,
            networkClient: NetworkClient? = null,
            circuitBreaker: CircuitBreaker? = null,
            dispatcher: Dispatcher? = null,
            diskStore: EventDiskStore? = null,
            networkMonitor: NetworkMonitor? = null
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
                dispatcher,
                diskStore,
                networkMonitor
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
    private lateinit var eventQueue: EventQueueInterface
    private lateinit var networkClient: NetworkClient
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var dispatcher: Dispatcher

    // Network monitoring
    private lateinit var networkMonitor: NetworkMonitor
    private var networkHandler: DebouncedNetworkHandler? = null

    // Persistence - null if an injected EventQueue was provided (bypasses persistence)
    private var persistableEventQueue: PersistableEventQueue? = null
    private var componentCallbacks: ComponentCallbacks2? = null

    /**
     * Internal initialization. Sets up all components.
     */
    private suspend fun initializeInternal() {
        if (!lifecycleState.compareAndSet(LifecycleState.IDLE, LifecycleState.INITIALIZING)) {
            Logger.warn("Attempted to initialize client that is not in IDLE state")
            return
        }
        // Initialization begins

        try {
            // Enable debug logging if requested
            if (options.debug) {
                Logger.debugEnabled = true
            }

            val channelCapacity = options.maxQueueEvents
            eventChannel = Channel(capacity = channelCapacity)

            // Initialize components (use injected or create new)
            identityManager = injectedIdentityManager ?: IdentityManager(context)
            contextProvider = injectedContextProvider ?: DeviceContextProvider(context)
            enrichmentService = injectedEnrichmentService ?: EventEnrichmentService(
                identityManager = identityManager,
                contextProvider = contextProvider,
                writeKey = options.writeKey
            )
            if (injectedEventQueue != null) {
                eventQueue = injectedEventQueue
            } else {
                val diskStore = injectedDiskStore ?: EventDiskStore.create(context)
                val pQueue = PersistableEventQueue(
                    maxCapacity = options.maxQueueEvents,
                    diskStore = diskStore
                )
                pQueue.rehydrate()
                eventQueue = pQueue
                persistableEventQueue = pQueue
            }

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
            identityManager.getAnonymousId()

            // Initialize network monitor with debounced handler
            networkMonitor = injectedNetworkMonitor ?: AndroidNetworkMonitor(context)
            val handler = DebouncedNetworkHandler(
                scope = scope,
                onOnline = {
                    circuitBreaker.reset()
                    dispatcher.resumeFromOffline()
                    dispatcher.flush()
                },
                onOffline = {
                    dispatcher.pauseForOffline()
                }
            )
            networkHandler = handler
            networkMonitor.start { connected -> handler.onConnectivityChanged(connected) }

            // If starting offline, pause dispatcher immediately
            if (!networkMonitor.isConnected) {
                dispatcher.pauseForOffline()
            }

            startEventProcessor()

            dispatcher.start()

            // Register best-effort terminate flush via ComponentCallbacks2
            persistableEventQueue?.let { pQueue ->
                val callbacks = object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                            Logger.log("onTrimMemory(TRIM_MEMORY_COMPLETE) - best-effort disk flush")
                            pQueue.flushToDisk()
                        }
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) {}
                    @Suppress("DEPRECATION")
                    override fun onLowMemory() {}
                }
                (context.applicationContext as? Application)?.registerComponentCallbacks(callbacks)
                componentCallbacks = callbacks
            }

            lifecycleState.set(LifecycleState.READY)
            Logger.log("MetaRouter SDK initialized")

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

        // Set identity synchronously to prevent race conditions with subsequent track() calls
        if (!identityManager.setUserId(userId)) {
            Logger.warn("Failed to set user ID")
            return
        }

        // Enqueue event async (non-blocking)
        scope.launch {
            try {
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

        // Set group synchronously to prevent race conditions with subsequent track() calls
        if (!identityManager.setGroupId(groupId)) {
            Logger.warn("Failed to set group ID")
            return
        }

        // Enqueue event async (non-blocking)
        scope.launch {
            try {
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

        // Set identity synchronously to prevent race conditions with subsequent track() calls
        if (!identityManager.setUserId(newUserId)) {
            Logger.warn("Failed to set user ID for alias")
            return
        }

        // Enqueue event async (non-blocking)
        scope.launch {
            try {
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

    override fun setAdvertisingId(advertisingId: String) {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot set advertisingId - SDK not ready")
            return
        }
        scope.launch {
            identityManager.setAdvertisingId(advertisingId)
        }
    }

    override fun clearAdvertisingId() {
        if (lifecycleState.get() != LifecycleState.READY) {
            Logger.warn("Cannot clear advertisingId - SDK not ready")
            return
        }
        scope.launch {
            identityManager.clearAdvertisingId()
        }
    }

    /**
     * Start the event processor coroutine.
     * This single consumer processes events sequentially, preventing unbounded concurrency.
     * Routes through dispatcher.offer() to enable auto-flush threshold checks.
     */
    private fun startEventProcessor() {
        scope.launch {
            for (baseEvent in eventChannel) {
                try {
                    val enrichedEvent = enrichmentService.enrichEvent(baseEvent)
                    dispatcher.offer(enrichedEvent)

                    // Check if disk flush threshold is reached
                    persistableEventQueue?.let { pQueue ->
                        if (pQueue.shouldFlushToDisk()) {
                            pQueue.flushToDisk()
                        }
                    }
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
        flush()
        persistableEventQueue?.flushToDisk()
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
            // Stop network monitor and cancel pending debounce
            networkHandler?.cancel()
            networkHandler = null
            networkMonitor.stop()

            // Stop dispatcher first
            dispatcher.stop()

            // Close event channel to stop processor coroutine
            eventChannel.close()

            // Unregister ComponentCallbacks2
            componentCallbacks?.let {
                (context.applicationContext as? Application)?.unregisterComponentCallbacks(it)
                componentCallbacks = null
            }

            // Cancel all coroutines in scope
            scope.cancel()

            // Clear event queue (also deletes disk snapshot via PersistableEventQueue override)
            eventQueue.clear()

            // Reset rehydration flag so a subsequent initialize() will reload from disk
            PersistableEventQueue.resetRehydrationFlag()

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
                put("networkStatus", if (networkMonitor.isConnected) "connected" else "disconnected")
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

    override fun setTracing(enabled: Boolean) {
        dispatcher.setTracing(enabled)
    }
}
