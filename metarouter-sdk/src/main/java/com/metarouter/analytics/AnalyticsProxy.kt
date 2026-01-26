package com.metarouter.analytics

import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Proxy implementation of [AnalyticsInterface] that queues calls before
 * the real client is bound, then replays them upon binding.
 */
class AnalyticsProxy(
    private val maxPendingCalls: Int = DEFAULT_MAX_PENDING_CALLS
) : AnalyticsInterface {

    companion object {
        const val DEFAULT_MAX_PENDING_CALLS = 2000
    }

    private val realClient = AtomicReference<AnalyticsInterface?>(null)
    private val pendingCalls = ArrayDeque<PendingCall>()
    private val mutex = Mutex()

    /**
     * Bind a real client and replay all pending calls.
     * This is idempotent - calling bind() again after binding is a no-op.
     */
    suspend fun bind(client: AnalyticsInterface) {
        mutex.withLock {
            if (realClient.get() != null) {
                Logger.warn("AnalyticsProxy already bound - ignoring duplicate bind")
                return
            }

            Logger.log("Binding AnalyticsProxy to real client, replaying ${pendingCalls.size} pending calls")

            // Replay all pending calls
            while (pendingCalls.isNotEmpty()) {
                val call = pendingCalls.removeFirst()
                replayCall(client, call)
            }

            // Set the real client atomically
            realClient.set(client)
            Logger.log("AnalyticsProxy bound successfully")
        }
    }

    /**
     * Check if a real client has been bound.
     */
    fun isBound(): Boolean = realClient.get() != null

    /**
     * Get the number of pending calls (for testing/debugging).
     */
    fun pendingCallCount(): Int = synchronized(pendingCalls) { pendingCalls.size }


    override fun track(event: String, properties: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.track(event, properties)
        } else {
            enqueue(PendingCall.Track(event, properties))
        }
    }

    override fun identify(userId: String, traits: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.identify(userId, traits)
        } else {
            enqueue(PendingCall.Identify(userId, traits))
        }
    }

    override fun group(groupId: String, traits: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.group(groupId, traits)
        } else {
            enqueue(PendingCall.Group(groupId, traits))
        }
    }

    override fun screen(name: String, properties: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.screen(name, properties)
        } else {
            enqueue(PendingCall.Screen(name, properties))
        }
    }

    override fun page(name: String, properties: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.page(name, properties)
        } else {
            enqueue(PendingCall.Page(name, properties))
        }
    }

    override fun alias(newUserId: String) {
        val client = realClient.get()
        if (client != null) {
            client.alias(newUserId)
        } else {
            enqueue(PendingCall.Alias(newUserId))
        }
    }

    override suspend fun flush() {
        val client = realClient.get()
        if (client != null) {
            client.flush()
        } else {
            enqueue(PendingCall.Flush)
        }
    }

    override suspend fun reset() {
        val client = realClient.get()
        if (client != null) {
            client.reset()
        } else {
            enqueue(PendingCall.Reset)
        }
    }

    override fun enableDebugLogging() {
        // Enable debug logging immediately on Logger
        Logger.debugEnabled = true

        val client = realClient.get()
        if (client != null) {
            client.enableDebugLogging()
        } else {
            enqueue(PendingCall.EnableDebugLogging)
        }
    }

    override suspend fun getDebugInfo(): Map<String, Any?> {
        val client = realClient.get()
        return if (client != null) {
            client.getDebugInfo()
        } else {
            mapOf(
                "lifecycle" to "initializing",
                "pendingCalls" to pendingCallCount(),
                "bound" to false
            )
        }
    }


    /**
     * Enqueue a pending call, dropping oldest if at capacity.
     */
    private fun enqueue(call: PendingCall) {
        synchronized(pendingCalls) {
            if (pendingCalls.size >= maxPendingCalls) {
                val dropped = pendingCalls.removeFirst()
                Logger.warn("Pending call queue at capacity ($maxPendingCalls), dropping oldest: ${dropped::class.simpleName}")
            }
            pendingCalls.addLast(call)
            Logger.log("Queued ${call::class.simpleName} (pending: ${pendingCalls.size})")
        }
    }

    /**
     * Replay a pending call on the given client.
     */
    private suspend fun replayCall(client: AnalyticsInterface, call: PendingCall) {
        when (call) {
            is PendingCall.Track -> client.track(call.event, call.properties)
            is PendingCall.Identify -> client.identify(call.userId, call.traits)
            is PendingCall.Group -> client.group(call.groupId, call.traits)
            is PendingCall.Screen -> client.screen(call.name, call.properties)
            is PendingCall.Page -> client.page(call.name, call.properties)
            is PendingCall.Alias -> client.alias(call.newUserId)
            is PendingCall.Flush -> client.flush()
            is PendingCall.Reset -> client.reset()
            is PendingCall.EnableDebugLogging -> client.enableDebugLogging()
        }
    }

    /**
     * Unbind the proxy and clear pending calls.
     * Used when resetting the SDK to allow re-initialization.
     */
    internal suspend fun unbind() {
        mutex.withLock {
            realClient.set(null)
            synchronized(pendingCalls) {
                pendingCalls.clear()
            }
        }
    }

    /**
     * Reset proxy state for testing. Alias for unbind().
     */
    internal suspend fun resetForTesting() = unbind()
}
