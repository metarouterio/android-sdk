package com.metarouter.analytics

import android.net.Uri
import android.webkit.WebView
import androidx.annotation.CheckResult
import com.metarouter.analytics.consent.Consent
import com.metarouter.analytics.consent.ConsentCategory
import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.consent.ConsentSubscription
import com.metarouter.analytics.identity.HostIdentity
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.utils.Logger
import com.metarouter.analytics.webview.BridgeMessageProcessor
import com.metarouter.analytics.webview.WebViewBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Proxy implementation of [AnalyticsInterface] that queues calls before
 * the real client is bound, then replays them upon binding.
 */
class AnalyticsProxy internal constructor(
    private val maxPendingCalls: Int,
    /**
     * Consent bypasses the pending-call queue entirely. The manager needs nothing from
     * an initialized client, so MetaRouter builds one per process and hands the same
     * instance to the proxy and to every client it binds. Queueing consent would lose
     * writes to the bind race and to queue eviction, and a pre-bind read would have to
     * lie — ignoring a queued setConsent. Compliance state gets none of those failure
     * modes: every call acts on the manager immediately, bound or not, and the client
     * bound later stamps from that same manager.
     */
    private val consentManager: ConsentManager,
    /**
     * Bypasses the pending-call queue for the same reasons as consent, plus one of its
     * own: the queue drops its OLDEST entry at capacity, so a host that sets a vendor id
     * and then tracks a burst of pre-initialize events would have the id evicted — the
     * one call that cannot be lost.
     */
    // No default: a caller passing only a shared consent manager would otherwise get a
    // fresh provider-context manager no client stamps from.
    private val providerContextManager: ProviderContextManager,
    /**
     * Before bind, setAnonymousId and the synchronous half of reset() land here, for the
     * same reasons as provider context. Every client's IdentityManager reads the
     * anonymousId from the same preferences, so a write landing between a client's
     * creation and its bind still reaches that client's next event.
     */
    private val hostIdentity: HostIdentity = HostIdentity(),
    // Waits for any initialize/reset/refusal already called, as iOS's settled(): a
    // read right after one must not be answered from the session before it.
    private val settled: suspend () -> Unit = {}
) : AnalyticsInterface {

    /**
     * A standalone proxy holds consent and provider context in memory only;
     * MetaRouter's attaches storage.
     */
    // @JvmOverloads: only a primary constructor gets Java's no-arg overload for free,
    // and `new AnalyticsProxy()` compiled against the previous release.
    @JvmOverloads
    constructor(maxPendingCalls: Int = DEFAULT_MAX_PENDING_CALLS) :
        this(maxPendingCalls, ConsentManager(), ProviderContextManager())

    companion object {
        const val DEFAULT_MAX_PENDING_CALLS = 2000

        // Passes over a backlog a host keeps refilling before the client is published
        // anyway; calls made after that may overtake the last pass's.
        private const val MAX_REPLAY_PASSES = 8
    }

    private val realClient = AtomicReference<AnalyticsInterface?>(null)

    // Runs the flush a bind replays, outside the bind.
    private val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingCalls = ArrayDeque<PendingCall>()
    private val mutex = Mutex()

    @Volatile
    private var boundSignal = CompletableDeferred<Unit>()

    // Bumped on the host's call site by every reset or disable (detachCalls), under the
    // pendingCalls monitor that a bind publishes its client under. A bind for an
    // initialize called before the bump must not publish: calls made after the reset
    // would replay into the client that reset is about to discard.
    private var epoch = 0

    // True while a bind replays the backlog, before the client is published. Guarded
    // by the pendingCalls monitor, like epoch.
    private var binding = false

    // Set when initialize was refused over invalid config: no bind is coming this
    // session, so awaiting APIs must resolve (degraded) instead of suspending forever
    // — a permanent hang would be a worse outcome than the crash this replaces.
    @Volatile
    private var configDisabled = false

    @Volatile
    private var configErrorDescription: String? = null

    /**
     * Bind a real client and replay all pending calls.
     * This is idempotent - calling bind() again after binding is a no-op.
     */
    suspend fun bind(client: AnalyticsInterface) {
        bind(client, expectedEpoch = null)
    }

    /**
     * Binds unless a reset or disable has detached the proxy since [epoch] was read,
     * returning whether it did.
     */
    internal suspend fun bindIfCurrent(client: AnalyticsInterface, epoch: Int): Boolean =
        bind(client, expectedEpoch = epoch)

    internal fun epoch(): Int = synchronized(pendingCalls) { epoch }

    /**
     * The call-site half of a reset or disable: a call made on the next line queues for
     * the next client instead of reaching the one being discarded. Calls queued before
     * it are dropped, as the queued teardown dropped them.
     */
    internal fun detachCalls() {
        synchronized(pendingCalls) {
            epoch++
            // Fresh signal before the client goes, as in unbind(): a waiter that sees
            // no client must also see a new signal to wait on.
            val outgoing = boundSignal
            boundSignal = CompletableDeferred()
            realClient.set(null)
            pendingCalls.clear()
            outgoing.complete(Unit)
        }
    }

    private suspend fun bind(client: AnalyticsInterface, expectedEpoch: Int?): Boolean {
        mutex.withLock {
            if (realClient.get() != null) {
                Logger.warn("AnalyticsProxy already bound - ignoring duplicate bind")
                return false
            }

            // MetaRouter attaches identity storage before building the client; a proxy
            // built without it lands its pre-bind setAnonymousId/reset notes here, before
            // the replay below runs the queued reset they order against.
            (client as? MetaRouterAnalyticsClient)?.let { hostIdentity.attachStorage(it.identityStorage) }
            synchronized(pendingCalls) { binding = true }

            // The client is published once the backlog is empty (or after
            // MAX_REPLAY_PASSES, so a host that keeps calling can't hold it), in the same
            // critical section on the pendingCalls monitor that enqueue() checks
            // realClient under. Until then a call made during replay queues behind the
            // backlog instead of overtaking it, and is replayed on the next pass; once
            // the client is set, no further call can be queued. Nothing can be stranded.
            //
            // Each replay is guarded individually: one throwing call (bad input,
            // platform exception) must not abort the bind — that would strand the
            // remaining queue and leave boundSignal incomplete.
            var replayed = 0
            var dropped = 0
            var signal: CompletableDeferred<Unit>? = null
            // A queued flush waits on the network. Run in the replay, it would hold
            // getAnonymousId and every other bind waiter for the request; it runs once,
            // after those waiters are released, and covers every call replayed before
            // it. It still runs under this bind's lock.
            var flushRequested = false
            fun current() = expectedEpoch == null || epoch == expectedEpoch
            var passes = 0
            try {
                while (signal == null) {
                    val pending = synchronized(pendingCalls) {
                        if (!current()) return@synchronized null
                        val calls = pendingCalls.toList().also { pendingCalls.clear() }
                        if (calls.isEmpty() || ++passes > MAX_REPLAY_PASSES) {
                            realClient.set(client)
                            signal = boundSignal
                        }
                        calls
                    } ?: break
                    for (call in pending) {
                        replayed++
                        if (call is PendingCall.Flush) {
                            flushRequested = true
                            continue
                        }
                        try {
                            replayCall(client, call)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            dropped++
                            Logger.error("Dropped queued ${call::class.simpleName} during replay", e)
                        }
                    }
                }
            } finally {
                // Cancelled mid-replay: still bind, as before, rather than leave every
                // later call queueing for a bind that already happened. What is still
                // queued can't replay on a cancelled coroutine; say so rather than hold it.
                val stranded = synchronized(pendingCalls) {
                    binding = false
                    if (signal == null && current()) {
                        realClient.set(client)
                        signal = boundSignal
                        pendingCalls.size.also { pendingCalls.clear() }
                    } else {
                        0
                    }
                }
                if (stranded > 0) Logger.warn("Bind cancelled - dropped $stranded queued call(s)")
                if (signal != null) {
                    // A successful (re)initialize supersedes an earlier config refusal.
                    configDisabled = false
                    configErrorDescription = null
                    signal?.complete(Unit)
                }
            }

            if (dropped > 0) {
                // Per-call errors scatter in the log; one summary line is the signal
                // that the pre-bind buffer (partially) failed to replay.
                Logger.warn("Replay dropped $dropped of $replayed queued call(s)")
            }
            if (flushRequested && signal != null) {
                // Not awaited: bind runs under the lifecycle lock, and getAnonymousId
                // waits for that lock, so awaiting would hold it for the request.
                flushScope.launch {
                    try {
                        client.flush()
                    } catch (e: Exception) {
                        Logger.error("Dropped queued Flush during replay", e)
                    }
                }
            }
            return signal != null
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
        // First, before any suspension or enqueue: this is the host's call site, while
        // the clear runs later (in the client, or on a replay at bind), where it would
        // also discard a setAnonymousId made after this call.
        noteResetRequested(client)
        if (client != null) {
            client.reset()
            return
        }
        // Decided under the monitor a bind publishes under, so a bind can't land in
        // between. With no client and none being bound, clear here: a queued Reset
        // would replay into the next client and leave it IDLE while bound. A bind in
        // progress gets the Reset queued behind the calls it is replaying, which
        // would otherwise write their identity back after a clear made here. (On a
        // standalone proxy with no storage attached yet, the clear is applied at
        // attach, once that bind's client is built.)
        val bound = synchronized(pendingCalls) {
            val current = realClient.get()
            when {
                current != null -> current
                binding -> enqueue(PendingCall.Reset)
                else -> {
                    // The user's queued calls go, as a bound client's reset drops its
                    // unsent events; settings stay.
                    pendingCalls.removeAll { it !is PendingCall.SetTracing && it !is PendingCall.EnableDebugLogging }
                    hostIdentity.clear()
                    null
                }
            }
        }
        bound?.reset()
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
                buildMap {
                    put("lifecycle", if (configDisabled) "disabled" else "initializing")
                    put("pendingCalls", pendingCallCount())
                    put("bound", false)
                    // The refused session has no client to report the error — the
                    // proxy carries it so getDebugInfo stays the one diagnostic API.
                    configErrorDescription?.let { put("configError", it) }
                    // Pre-bind there is no client to report consent; the manager
                    // exists regardless, and "what consent is the SDK holding before
                    // init" is exactly what someone debugging a CMP-first launch
                    // wants to see.
                    put("consent", consentManager.getConsent().toDebugInfo())
                    put("providerContext", providerContextManager.toDebugInfo())
                }
            }
        }
    }

    /**
     * The synchronous half of every host reset entry point (this proxy's [reset] and
     * `MetaRouter.Analytics.reset`/`resetAndWait`). Routed like [setAnonymousId] so both
     * touch the same storage.
     */
    internal fun noteResetRequested(client: AnalyticsInterface? = realClient.get()) {
        (client as? MetaRouterAnalyticsClient)?.noteResetRequested() ?: hostIdentity.noteResetRequested()
    }

    override fun setAnonymousId(anonymousId: String): Boolean {
        val client = realClient.get() as? MetaRouterAnalyticsClient
        return client?.setAnonymousId(anonymousId) ?: hostIdentity.setAnonymousId(anonymousId)
    }

    override suspend fun getAnonymousId(): String {
        settled()
        while (true) {
            val signal = boundSignal
            signal.await()
            realClient.get()?.let { return it.getAnonymousId() }
            if (configDisabled) {
                // Empty only on a config-disabled session — the degraded-but-resolved
                // answer; normal operation still awaits binding and never returns empty.
                return ""
            }
            // Woke with no client and no refusal: a concurrent clearConfigDisabled()
            // (or unbind()) replaced the signal between our wake-up and these reads —
            // the completed verdict belonged to the previous session and a new bind is
            // incoming on the fresh signal. Await that one instead of throwing; only a
            // wake-up from the CURRENT signal in this state is a genuine invariant
            // breach.
            if (boundSignal === signal) {
                throw IllegalStateException(
                    "AnalyticsProxy bound signal completed but client is null (likely unbound during getAnonymousId)"
                )
            }
        }
    }

    override suspend fun getSessionId(): String? {
        // No await-for-bind, unlike getAnonymousId: sessions are minted by
        // events, so before a client is bound "null — no session yet" is the
        // correct, documented answer. Suspending here would turn a prompt
        // diagnostic read into a process-lifetime hang whenever initialize()
        // is never called (or a reset() is never followed by one). The same
        // null also serves the config-disabled session without a sentinel.
        // Also null from reset() until the next client is bound, although the
        // persisted session carries on into it.
        return realClient.get()?.getSessionId()
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
            enqueue(PendingCall.RecordOpenedUrl(uri, sourceApplication))?.recordOpenedUrl(uri, sourceApplication)
        }
    }

    // The bridge is owned here, not by a client: clients are disposable (reset()
    // creates a new one) while attached WebViews live on, and the platform has no API
    // to remove a WebMessageListener. The sink resolves "the current client" at
    // delivery time — a webview attached before init, or surviving a reset/re-init
    // cycle, delivers to whichever client is bound when its events arrive, and events
    // arriving with no ready client are NAKed not_ready instead of silently lost.
    private val bridgeProcessor by lazy {
        BridgeMessageProcessor(sink = { envelope ->
            (realClient.get() as? MetaRouterAnalyticsClient)?.enqueueBridgeEvent(envelope) ?: false
        })
    }

    override fun attachWebView(webView: WebView, allowedOrigins: List<String>) {
        // Registers immediately regardless of bind state — the WebView registrations
        // only apply to page loads that start afterwards, so deferring to bind-replay
        // would lose the wrapper on the host's first loadUrl.
        WebViewBridge.attach(webView, allowedOrigins, bridgeProcessor)
    }

    // ===== Consent (direct, never queued — see consentManager) =====

    override fun registerConsentCategories(categories: List<ConsentCategory>) {
        consentManager.registerConsentCategories(categories)
    }

    override fun setConsent(consent: Consent): Boolean = consentManager.setConsent(consent)

    override fun getConsent(): Consent = consentManager.getConsent()

    override fun clearConsent() {
        consentManager.clearConsent()
    }

    @CheckResult
    override fun subscribeToConsent(handler: (Consent) -> Unit): ConsentSubscription =
        ConsentSubscription(consentManager.subscribe(handler))

    // ===== Provider context (direct, never queued — see providerContextManager) =====

    override fun setProviderContext(provider: String, key: String, value: Any?): Boolean =
        providerContextManager.setValue(provider, key, value)

    override fun getProviderContext(): Map<String, JsonObject> = providerContextManager.getAll()

    override fun clearProviderContext(provider: String) {
        providerContextManager.clear(provider)
    }

    override fun clearAllProviderContext() {
        providerContextManager.clearAll()
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
     * Unbind the proxy and clear pending calls. Tests only: reset and disable use
     * [detachCalls] then [release].
     */
    internal suspend fun unbind() = teardown(dropPending = true)

    /**
     * The queued half of a reset or disable: unbinds like [unbind] but keeps the calls
     * made since [detachCalls], which wait for the next client.
     */
    internal suspend fun release() = teardown(dropPending = false)

    private suspend fun teardown(dropPending: Boolean) {
        mutex.withLock {
            // Publish the fresh signal FIRST, tear state down, and complete the
            // outgoing signal LAST. A waiter parked on the outgoing signal then
            // wakes only after every newer write is visible: it reads
            // client-null / flag-false, sees boundSignal is a different
            // instance, and re-awaits the fresh one — resolving against
            // whatever this session becomes (a refusal's markConfigDisabled,
            // or the next bind). Abandoning the outgoing signal uncompleted
            // strands every pre-teardown waiter asleep forever — the exact
            // hang the resolve-degraded contract exists to prevent — and
            // completing it before the swap is visible reintroduces the
            // client-null/flag-false/same-signal throw.
            // Under the pendingCalls monitor, as in detachCalls: a swap there between
            // our read and write would be lost, stranding its waiters.
            val outgoing = synchronized(pendingCalls) {
                val outgoing = boundSignal
                boundSignal = CompletableDeferred()
                realClient.set(null)
                if (dropPending) pendingCalls.clear()
                // reset() tears down a session without refusing one. Leaving the flag set
                // would make every later awaiting call resolve degraded for a session
                // that was never gated. disableSession() marks it again after this.
                configDisabled = false
                configErrorDescription = null
                outgoing
            }
            outgoing.complete(Unit)
        }
    }

    /**
     * Invalid-config refusal: no bind is coming this session, so waiters must
     * resolve degraded rather than suspend forever. Completing the (fresh, post-
     * unbind) bound signal is what wakes them; they see no client, flag set.
     */
    internal suspend fun markConfigDisabled(errorDescription: String) {
        mutex.withLock {
            synchronized(pendingCalls) {
                configDisabled = true
                configErrorDescription = errorDescription
                boundSignal.complete(Unit)
            }
        }
    }

    /**
     * Clears the refusal ahead of a bind that is still being built, so callers
     * awaiting in the pre-bind window suspend for the incoming client instead of
     * resolving degraded against the previous session's verdict. The refused
     * session's bound signal is already completed, so a fresh one is required.
     */
    internal suspend fun clearConfigDisabled() {
        mutex.withLock {
            val outgoing = synchronized(pendingCalls) {
                if (!configDisabled) return
                // Fresh signal BEFORE clearing the flag: a waiter woken by the
                // refusal reads the flag and then the signal, lock-free. In
                // that order of volatile writes, a waiter that observed
                // flag=false is guaranteed to also observe the swapped signal
                // and re-await it; cleared-flag-first leaves a window where it
                // reads flag=false with the awaited signal still current — the
                // invariant-breach throw, on exactly the refusal→recovery
                // sequence this method exists to serve.
                val outgoing = boundSignal
                boundSignal = CompletableDeferred()
                configDisabled = false
                configErrorDescription = null
                outgoing
            }
            // Usually the refusal's, already completed; after a detachCalls it is a
            // fresh one with waiters parked on it, who re-await the new signal.
            outgoing.complete(Unit)
        }
    }

    /**
     * Reset proxy state for testing. Alias for unbind().
     */
    internal suspend fun resetForTesting() = unbind()
}
