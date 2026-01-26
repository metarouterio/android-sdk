package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton facade for the MetaRouter Analytics SDK.
 *
 * Provides two initialization patterns:
 * 1. Sync: [createAnalyticsClient] returns a proxy immediately, binds async
 * 2. Async: [initializeAndWait] awaits binding before returning
 *
 */
object MetaRouter {
    private val proxy = AnalyticsProxy()
    private val store = RealClientStore()
    private val initMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var initializationStarted = false

    /**
     * Create an analytics client with synchronous return.
     *
     * Returns a proxy immediately that queues calls until the real client
     * is initialized in the background. This is the recommended approach
     * for most applications.
     *
     * @param context Android application context
     * @param options Configuration options
     * @return AnalyticsInterface proxy that can be used immediately
     */
    fun createAnalyticsClient(context: Context, options: InitOptions): AnalyticsInterface {
        if (initializationStarted) {
            Logger.warn("MetaRouter already initialized - returning existing proxy")
            return proxy
        }

        initializationStarted = true
        Logger.log("MetaRouter.createAnalyticsClient starting async initialization")

        scope.launch {
            try {
                initializeInternal(context, options)
            } catch (e: Exception) {
                Logger.error("Background initialization failed: ${e.message}")
                // Reset flag to allow retry
                initializationStarted = false
            }
        }

        return proxy
    }

    /**
     * Initialize and wait for the client to be fully ready.
     *
     * This suspends until initialization is complete and the proxy is bound.
     * Use this when you need to ensure the client is ready before proceeding.
     *
     * @param context Android application context
     * @param options Configuration options
     * @return AnalyticsInterface that is fully initialized
     * @throws Exception if initialization fails
     */
    suspend fun initializeAndWait(context: Context, options: InitOptions): AnalyticsInterface {
        if (proxy.isBound()) {
            Logger.warn("MetaRouter already initialized - returning existing proxy")
            return proxy
        }

        initializationStarted = true
        initializeInternal(context, options)
        return proxy
    }

    /**
     * Internal initialization logic with mutex protection.
     */
    private suspend fun initializeInternal(context: Context, options: InitOptions) {
        initMutex.withLock {
            // Double-check after acquiring lock
            if (proxy.isBound()) {
                Logger.log("Client already bound, skipping initialization")
                return
            }

            Logger.log("Initializing MetaRouterAnalyticsClient...")
            val client = MetaRouterAnalyticsClient.initialize(context, options)

            val stored = store.set(client)
            if (!stored) {
                Logger.warn("Another client was stored while initializing - this should not happen")
            }

            proxy.bind(client)
            Logger.log("MetaRouter initialization complete")
        }
    }

    object Analytics {
        /**
         * Initialize the analytics client (fire-and-forget).
         *
         * @param context Android application context
         * @param options Configuration options
         * @return AnalyticsInterface proxy that can be used immediately
         */
        fun initialize(context: Context, options: InitOptions): AnalyticsInterface {
            return createAnalyticsClient(context, options)
        }

        /**
         * Initialize and wait for the client to be fully ready.
         *
         * @param context Android application context
         * @param options Configuration options
         * @return AnalyticsInterface that is fully initialized
         */
        suspend fun initializeAndWait(context: Context, options: InitOptions): AnalyticsInterface {
            return MetaRouter.initializeAndWait(context, options)
        }

        /**
         * Get the current analytics client.
         *
         * @return The analytics interface (proxy)
         * @throws IllegalStateException if not initialized
         */
        fun client(): AnalyticsInterface {
            if (!initializationStarted) {
                throw IllegalStateException(
                    "MetaRouter not initialized. Call MetaRouter.Analytics.initialize() first."
                )
            }
            return proxy
        }

        /**
         * Reset the SDK state (fire-and-forget).
         *
         * Clears identity and queued events. After reset, you must
         * reinitialize before using the SDK.
         */
        fun reset() {
            if (!initializationStarted) {
                Logger.warn("Cannot reset - MetaRouter not initialized")
                return
            }

            scope.launch {
                resetInternal()
            }
        }

        /**
         * Reset the SDK state and wait for completion.
         */
        suspend fun resetAndWait() {
            if (!initializationStarted) {
                Logger.warn("Cannot reset - MetaRouter not initialized")
                return
            }

            resetInternal()
        }

        /**
         * Enable or disable debug logging.
         *
         * @param enabled true to enable debug logging
         */
        fun setDebugLogging(enabled: Boolean) {
            Logger.debugEnabled = enabled
            if (enabled) {
                Logger.log("Debug logging enabled via MetaRouter.Analytics.setDebugLogging()")
            }
        }

        private suspend fun resetInternal() {
            initMutex.withLock {
                Logger.log("Resetting MetaRouter...")

                // Get the stored client and reset it
                val client = store.get()
                client?.reset()

                // Clear the store
                store.clear()

                // Reset initialization flag
                initializationStarted = false

                Logger.log("MetaRouter reset complete - re-initialization required")
            }
        }
    }

    /**
     * Reset internal state for testing.
     * This should only be used in tests.
     */
    internal suspend fun resetForTesting() {
        initMutex.withLock {
            store.clear()
            initializationStarted = false
        }
    }
}
