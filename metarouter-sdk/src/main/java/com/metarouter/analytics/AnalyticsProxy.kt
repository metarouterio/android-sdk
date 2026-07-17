package com.metarouter.analytics

import android.net.Uri
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

    @Volatile
    private var boundSignal = CompletableDeferred<Unit>()

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

            // Publish the client and snapshot the backlog in one critical section on
            // the pendingCalls monitor. enqueue() checks realClient under that same
            // monitor, so once the client is set no further call can be queued: a call
            // racing this transition either lands in `pending` (replayed below) or sees
            // the client in enqueue() and runs directly. Nothing can be stranded.
            val pending = synchronized(pendingCalls) {
                realClient.set(client)
                val calls = pendingCalls.toList()
                pendingCalls.clear()
                calls
            }
            boundSignal.complete(Unit)

            // Each replay is guarded individually: one throwing call (bad input,
            // platform exception) must not abort the bind — that would strand the
            // remaining queue and leave boundSignal incomplete.
            var dropped = 0
            for (call in pending) {
                try {
                    replayCall(client, call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropped++
                    Logger.error("Dropped queued ${call::class.simpleName} during replay", e)
                }
            }
            if (dropped > 0) {
                // Per-call errors scatter in the log; one summary line is the signal
                // that the pre-bind buffer (partially) failed to replay.
                Logger.warn("Replay dropped $dropped of ${pending.size} queued call(s)")
            }
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
            enqueue(PendingCall.Track(event, properties))?.track(event, properties)
        }
    }

    override fun identify(userId: String, traits: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.identify(userId, traits)
        } else {
            enqueue(PendingCall.Identify(userId, traits))?.identify(userId, traits)
        }
    }

    override fun group(groupId: String, traits: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.group(groupId, traits)
        } else {
            enqueue(PendingCall.Group(groupId, traits))?.group(groupId, traits)
        }
    }

    override fun screen(name: String, properties: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.screen(name, properties)
        } else {
            enqueue(PendingCall.Screen(name, properties))?.screen(name, properties)
        }
    }

    override fun page(name: String, properties: Map<String, Any?>?) {
        val client = realClient.get()
        if (client != null) {
            client.page(name, properties)
        } else {
            enqueue(PendingCall.Page(name, properties))?.page(name, properties)
        }
    }

    override fun alias(newUserId: String) {
        val client = realClient.get()
        if (client != null) {
            client.alias(newUserId)
        } else {
            enqueue(PendingCall.Alias(newUserId))?.alias(newUserId)
        }
    }

    override fun setAdvertisingId(advertisingId: String) {
        val client = realClient.get()
        if (client != null) {
            client.setAdvertisingId(advertisingId)
        } else {
            enqueue(PendingCall.SetAdvertisingId(advertisingId))?.setAdvertisingId(advertisingId)
        }
    }

    override fun clearAdvertisingId() {
        val client = realClient.get()
        if (client != null) {
            client.clearAdvertisingId()
        } else {
            enqueue(PendingCall.ClearAdvertisingId)?.clearAdvertisingId()
        }
    }

    override suspend fun flush() {
        val client = realClient.get()
        if (client != null) {
            client.flush()
        } else {
            enqueue(PendingCall.Flush)?.flush()
        }
    }

    override suspend fun reset() {
        val client = realClient.get()
        if (client != null) {
            client.reset()
        } else {
            enqueue(PendingCall.Reset)?.reset()
        }
    }

    override fun enableDebugLogging() {
        // Enable debug logging immediately on Logger
        Logger.debugEnabled = true

        val client = realClient.get()
        if (client != null) {
            client.enableDebugLogging()
        } else {
            enqueue(PendingCall.EnableDebugLogging)?.enableDebugLogging()
        }
    }

    override suspend fun getDebugInfo(): Map<String, Any?> {
        // Use mutex to ensure consistent read with bind/unbind operations
        return mutex.withLock {
            val client = realClient.get()
            if (client != null) {
                client.getDebugInfo() + ("bound" to true)
            } else {
                mapOf(
                    "lifecycle" to "initializing",
                    "pendingCalls" to pendingCallCount(),
                    "bound" to false
                )
            }
        }
    }

    override suspend fun getAnonymousId(): String {
        val signal = boundSignal
        signal.await()
        val client = realClient.get()
            ?: throw IllegalStateException(
                "AnalyticsProxy bound signal completed but client is null (likely unbound during getAnonymousId)"
            )
        return client.getAnonymousId()
    }

    override fun setTracing(enabled: Boolean) {
        val client = realClient.get()
        if (client != null) {
            client.setTracing(enabled)
        } else {
            enqueue(PendingCall.SetTracing(enabled))?.setTracing(enabled)
        }
    }

    override fun recordOpenedUrl(uri: Uri, sourceApplication: String?) {
        val client = realClient.get()
        if (client != null) {
            client.recordOpenedUrl(uri, sourceApplication)
        } else {
            enqueue(PendingCall.RecordOpenedUrl(uri, sourceApplication))
        }
    }


    /**
     * Enqueue a pending call, dropping oldest if at capacity.
     *
     * Re-checks realClient under the pendingCalls monitor: if the proxy bound between
     * the caller's unlocked check and now, the call is NOT queued (it would never be
     * replayed) — the bound client is returned so the caller runs the call directly.
     * Returns null when the call was queued.
     */
    private fun enqueue(call: PendingCall): AnalyticsInterface? {
        synchronized(pendingCalls) {
            val client = realClient.get()
            if (client != null) return client
            if (pendingCalls.size >= maxPendingCalls) {
                val dropped = pendingCalls.removeFirst()
                Logger.warn("Pending call queue at capacity ($maxPendingCalls), dropping oldest: ${dropped::class.simpleName}")
            }
            pendingCalls.addLast(call)
            return null
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
            is PendingCall.SetTracing -> client.setTracing(call.enabled)
            is PendingCall.SetAdvertisingId -> client.setAdvertisingId(call.advertisingId)
            is PendingCall.ClearAdvertisingId -> client.clearAdvertisingId()
            is PendingCall.RecordOpenedUrl -> client.recordOpenedUrl(call.uri, call.sourceApplication)
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
            boundSignal = CompletableDeferred()
        }
    }

    /**
     * Reset proxy state for testing. Alias for unbind().
     */
    internal suspend fun resetForTesting() = unbind()
}
