package com.metarouter.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import com.metarouter.analytics.lifecycle.AppLifecycleObserver
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Atomic flag to ensure only one initialization attempt proceeds. */
    private val initializationStarted = AtomicBoolean(false)

    /**
     * Set when the last initialize was refused over invalid config. A refused
     * session marks initializationStarted (so client() stays call-safe) — this flag
     * is what lets the next valid initialize proceed anyway instead of bouncing off
     * that guard, so a host that fixes its config and re-initializes recovers.
     */
    private val sessionRefused = AtomicBoolean(false)

    @Volatile
    private var lifecycleObserver: AppLifecycleObserver? = null

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
        if (!passesConfigGate(context, options)) {
            // Marked started so client() hands back the inert proxy instead of
            // throwing — the refused session must stay call-safe end to end.
            initializationStarted.set(true)
            sessionRefused.set(true)
            scope.launch { disableSession(options.configError!!) }
            return proxy
        }

        // Atomic check-and-set prevents race conditions when called from multiple
        // threads. A refused session is the exception: exactly one valid call may
        // proceed past the guard to recover it.
        if (!initializationStarted.compareAndSet(false, true) &&
            !sessionRefused.compareAndSet(true, false)
        ) {
            return proxy
        }
        sessionRefused.set(false)

        Logger.log("MetaRouter.createAnalyticsClient starting async initialization")

        scope.launch {
            try {
                initializeInternal(context, options)
            } catch (e: Exception) {
                Logger.error("Background initialization failed: ${e.message}")
                initializationStarted.set(false)
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
        if (!passesConfigGate(context, options)) {
            initializationStarted.set(true)
            sessionRefused.set(true)
            disableSession(options.configError!!)
            return proxy
        }
        sessionRefused.set(false)

        if (proxy.isBound()) {
            Logger.warn("MetaRouter already initialized - returning existing proxy")
            initializationStarted.set(true)
            return proxy
        }

        initializationStarted.set(true)
        initializeInternal(context, options)
        return proxy
    }

    /**
     * The release half of the invalid-config contract: construction recorded the
     * verdict, this is where it takes effect. On invalid config no client is created —
     * the proxy is still returned so call sites never see null, and the SDK is inert
     * for the session, mirroring the 401/403/404 graceful-disable for local config.
     * Log and callback fire synchronously on the caller's thread.
     *
     * Debuggable builds fail fast here instead: construction has no `Context`, so
     * this is the earliest point where debuggability is knowable — the fork lives at
     * initialize on both platforms for that reason.
     */
    private fun passesConfigGate(context: Context, options: InitOptions): Boolean {
        val error = options.configError ?: return true
        if (isDebuggableBuild(context)) {
            throw IllegalArgumentException("Invalid InitOptions: ${error.description}")
        }
        Logger.error("MetaRouter disabled for this session — ${error.description}")
        options.onConfigError?.invoke(error)
        return false
    }

    private fun isDebuggableBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * An initialize call with invalid config disables the whole session, even a
     * re-initialize over a previously valid client: silently keeping the stale
     * client running would mask the config error. Fail visible, not quiet. The
     * discarded client is reset — dropping it live would leave its dispatcher
     * flushing, and a "disabled" session must not keep sending events. Marking the
     * proxy disabled also resolves suspended awaiters — no bind is coming.
     */
    private suspend fun disableSession(error: ConfigError) {
        initMutex.withLock {
            lifecycleObserver?.unregister()
            lifecycleObserver = null
            store.get()?.reset()
            store.clear()
            proxy.unbind()
            proxy.markConfigDisabled(error.description)
        }
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

            // Clear any prior refusal before building the client, so callers awaiting
            // in this window suspend for the incoming bind instead of resolving
            // degraded against the previous session's verdict.
            proxy.clearConfigDisabled()

            // The config-error callback only ever fires from the gate; the client's
            // copy of the options must not pin the closure for the session. Dropping
            // it goes through copy(), which re-runs the validation warnings — only
            // pay that (duplicate log lines) when there is a callback to drop.
            val gatedOptions =
                if (options.onConfigError != null) options.discardingConfigCallback() else options
            val client = MetaRouterAnalyticsClient.initialize(context, gatedOptions)

            val stored = store.set(client)
            if (!stored) {
                Logger.warn("Another client was stored while initializing - this should not happen")
            }


            lifecycleObserver = AppLifecycleObserver(
                scope = scope,
                onForeground = { client.onForeground() },
                onBackground = { client.onBackground() }
            )
            lifecycleObserver?.register()

            proxy.bind(client)
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
            if (!initializationStarted.get()) {
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
            if (!initializationStarted.get()) {
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
            if (!initializationStarted.get()) {
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

                // Unregister lifecycle observer
                lifecycleObserver?.unregister()
                lifecycleObserver = null

                // Get the stored client and reset it
                val client = store.get()
                client?.reset()

                // Clear the store
                store.clear()

                // Reset the proxy so it can be bound to a new client
                proxy.unbind()

                // Reset initialization flag
                initializationStarted.set(false)
                sessionRefused.set(false)

                Logger.log("MetaRouter reset complete - re-initialization required")
            }
        }
    }

    /**
     * Reset internal state for testing.
     * This should only be used in tests.
     */
    internal suspend fun resetForTesting() {
        // Cancel any pending async initializations (e.g., lingering createAnalyticsClient launches)
        scope.coroutineContext[Job]?.cancelChildren()

        initMutex.withLock {
            lifecycleObserver?.unregister()
            lifecycleObserver = null
            store.clear()
            proxy.resetForTesting()
            initializationStarted.set(false)
            sessionRefused.set(false)
        }
    }
}
