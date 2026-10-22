package com.metarouter.analytics.consent

import android.os.Handler
import android.os.Looper
import com.metarouter.analytics.utils.Logger

/**
 * Owns the consent state machine: the category registry, the current reconciled view,
 * persistence, and change subscriptions. The public API delegates here; payload-attach
 * reads [getConsent] at enrichment time.
 *
 * A lock-based class rather than a coroutine-confined one, on purpose. `setConsent`
 * must be read-your-writes synchronous: a host that calls `setConsent` and then
 * `track` on the next line expects the enrichment of that event to see the new state,
 * and a dispatcher hop would let it read the old one. The same lock makes
 * [getConsent] a plain synchronous call, which is what a CMP integration expects from
 * a "what is consent right now" read.
 *
 * Subscriber callbacks are *enqueued* on `dispatch` while the lock is held and, with
 * the production main-looper post, run later off the lock. Enqueuing inside the lock
 * is what makes delivery order equal commit order — enqueuing outside it let a
 * preempted thread deliver an older state after a newer one, leaving a subscriber
 * settled on consent the user had already changed. `dispatch` must be asynchronous:
 * an inline dispatcher runs the handler under the lock, and a handler that calls back
 * into the manager re-enters mid-commit (JVM monitors are reentrant, so it does not
 * deadlock — it reads half-notified state instead).
 *
 * Persistence happens AFTER the lock is released. A `SharedPreferences` change
 * listener registered by the host runs synchronously when `apply()` is called on the
 * main thread, and one that reads consent back must not run inside a commit. A commit
 * sequence number keeps disk writes in commit order.
 *
 * @param clockMillis stamps a `setConsent` that arrives without a timestamp.
 * @param dispatch where subscriber callbacks run. Null (production) posts to the main
 *   looper, like every UI-adjacent listener API on the platform. Tests inject a
 *   recorder they drain explicitly; an injected dispatcher that runs work before
 *   returning, on the enqueuing thread, throws.
 */
internal class ConsentManager(
    storage: ConsentStorage,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    dispatch: ((Runnable) -> Unit)? = null
) {

    /**
     * One subscription. [cancel] is checked again at delivery time: unsubscribing must
     * also drop deliveries that were already enqueued but have not run yet — a host
     * that unsubscribes in `onDestroy` must not get one more callback on the next
     * looper turn into an object it considers gone.
     */
    private class Subscription(handler: (Consent) -> Unit) {
        /**
         * Null once cancelled, so a retained unsubscribe does not keep the handler's
         * captures (often an Activity) alive for the life of the process-wide manager.
         */
        @Volatile
        var activeHandler: ((Consent) -> Unit)? = handler
            private set

        fun cancel() {
            activeHandler = null
        }
    }

    /**
     * Throws on a dispatch that runs work before returning, on the enqueuing thread:
     * that work runs under the manager lock. Only wraps injected dispatchers — the
     * production main-looper post is asynchronous by construction.
     */
    private class InlineDispatchCheck {
        private val thread = Thread.currentThread()

        @Volatile
        private var returned = false

        fun markReturned() {
            returned = true
        }

        fun assertNotInline() {
            check(Thread.currentThread() !== thread || returned) {
                "ConsentManager dispatch ran a handler inline under the manager lock; it must be asynchronous"
            }
        }
    }

    private val lock = Any()
    private var storage: ConsentStorage = storage
    private val dispatch: (Runnable) -> Unit = dispatch?.let { injected -> checkedDispatch(injected) }
        ?: mainLooperDispatch()

    private var registry: List<ConsentCategory> = emptyList()

    // Recomputed only when the registry changes: getConsent() sits on the per-event
    // enrichment path, and rebuilding the default map per event is avoidable work.
    private var implicitDefault: Consent = ConsentReconciler.implicitDefault(emptyList())

    /** Null = the host has never set consent (or cleared it). */
    // Loaded eagerly: the manager is built alongside the identity read from the same
    // process start, and a consent persisted by a prior launch must be visible to the
    // very first read.
    private var stored: StoredConsent? = storage.get()

    /** [stored] projected onto [registry]; recomputed on every set and registration. */
    private var reconciled: Consent? = stored?.let { ConsentReconciler.reconcile(it.consent, emptyList()) }

    private val subscribers = mutableListOf<Subscription>()
    private var warnedEmptyRegistry = false
    private var registrationProblems: List<String> = emptyList()

    // Commit ordering for off-lock persistence. A separate (reentrant) monitor: a
    // persist can re-enter setConsent through a host's preference listener, and that
    // nested commit must be able to persist too (it carries a higher sequence, so it
    // wins).
    private var commitSequence = 0L
    private val persistLock = Any()
    private var persistedSequence = 0L

    /** What readers currently see. Caller holds [lock]. */
    private fun visibleLocked(): Consent = reconciled ?: implicitDefault

    // ===== Registry =====

    /**
     * Replaces (not merges) the registered category set and recomputes the view of
     * any stored consent against it.
     *
     * Recomputing is what keeps the block complete across app updates: a snapshot
     * stored by a build that registered three categories would otherwise reach the
     * server missing the fourth one this build added — and a missing key is not read
     * as blocked downstream. The stored decision is preserved for every category it
     * covers (storage holds the raw snapshot, so a category dropped by one build and
     * re-added by the next comes back with the user's value); only categories the
     * snapshot never mentioned take their registered default.
     *
     * Misconfigured input degrades instead of throwing — a registration typo must not
     * take the host down: keys are trimmed and lowercased (incoming optOut keys are
     * canonicalized the same way before matching), blank keys are dropped, and
     * duplicates keep their first occurrence. Each case warns and is kept in
     * [registrationProblems] for the debuggable-build check.
     */
    fun registerConsentCategories(categories: List<ConsentCategory>) {
        val problems = mutableListOf<String>()
        val normalized = normalize(categories, problems)
        problems.forEach(Logger::warn)

        synchronized(lock) {
            val before = visibleLocked()
            registry = normalized
            registrationProblems = problems
            implicitDefault = ConsentReconciler.implicitDefault(normalized)
            reconciled = stored?.let { ConsentReconciler.reconcile(it.consent, normalized) }
            notifyIfChangedLocked(before)
        }
    }

    /**
     * Problems the latest registration degraded past. Kept rather than thrown: the
     * manager has no `Context`, so it cannot tell a debuggable build from a release
     * one — the caller that can decides whether they fail fast.
     */
    fun registrationProblems(): List<String> = synchronized(lock) { registrationProblems }

    private fun normalize(categories: List<ConsentCategory>, problems: MutableList<String>): List<ConsentCategory> {
        val seen = HashSet<String>()
        val result = ArrayList<ConsentCategory>(categories.size)
        for (category in categories) {
            val key = ConsentKey.canonical(category.key)
            if (key.isEmpty()) {
                problems += "registerConsentCategories: blank category key dropped"
                continue
            }
            if (key != category.key) {
                problems += "registerConsentCategories: key \"${category.key}\" normalized to \"$key\" — " +
                    "optOut keys are lowercase and matched exactly"
            }
            if (!seen.add(key)) {
                problems += "registerConsentCategories: duplicate key \"$key\" — keeping the first occurrence"
                continue
            }
            result += ConsentCategory(key, category.defaultOptOut)
        }
        return result
    }

    // ===== State =====

    /**
     * Current consent. Never null: before the first `setConsent` (and after
     * `clearConsent`) this is the implicit default — `explicit: false`, registered
     * categories at their defaults — so payload-attach always has a complete block.
     */
    fun getConsent(): Consent = synchronized(lock) { visibleLocked() }

    /**
     * Reconciles, stores, persists, and notifies. Fully replaces prior state.
     *
     * @return `false` when the update was ignored as stale: it carries a host-supplied
     *   timestamp older than the newest host-supplied timestamp seen since the last
     *   `clearConsent`. A CMP replaying a cached choice must not overwrite the fresher
     *   one the user just made.
     *
     * Two timestamps are only comparable when the same party stamped them. The SDK's
     * stamp is the device wall clock — which the host's CMP server time can trail by
     * minutes, and which jumps backwards on its own (NTP, a user resetting the date).
     * Comparing across those clocks rejected real choices: a banner callback carrying
     * server time lost to an untimestamped regional default stamped moments earlier by
     * a fast device clock. So an untimestamped call is always applied as the user's
     * choice as of now and stamped with the device clock (informational only —
     * `getConsent().timestamp` is not monotonic), and a host-timestamped call is
     * checked only against the host watermark, which an untimestamped call carries
     * forward rather than resets. `clearConsent` resets the watermark: a withdrawal
     * starts arbitration over.
     */
    fun setConsent(consent: Consent): Boolean {
        var incomingConsent = consent
        val hostTimestamp = consent.timestamp
        if (hostTimestamp != null && hostTimestamp > MAX_PLAUSIBLE_HOST_TIMESTAMP) {
            Logger.warn("setConsent: timestamp $hostTimestamp is not epoch milliseconds — applied as untimestamped")
            incomingConsent = consent.copy(timestamp = null)
        }

        var warnEmptyRegistry = false
        val pending = synchronized(lock) {
            val watermark = stored?.lastHostTimestamp
            val incomingTimestamp = incomingConsent.timestamp
            val incoming = if (incomingTimestamp != null) {
                if (watermark != null && incomingTimestamp < watermark) {
                    Logger.warn(
                        "setConsent ignored: snapshot ($incomingTimestamp) is older than the newest " +
                            "host-timestamped state ($watermark)"
                    )
                    return false
                }
                StoredConsent(incomingConsent, lastHostTimestamp = incomingTimestamp)
            } else {
                StoredConsent(incomingConsent.copy(timestamp = clockMillis()), lastHostTimestamp = watermark)
            }

            if (registry.isEmpty() && !warnedEmptyRegistry) {
                // Once per process: the story asks for a warning on this path, and a
                // host that never registers would otherwise log on every CMP callback.
                warnedEmptyRegistry = true
                warnEmptyRegistry = true
            }
            commitLocked(incoming)
        }

        if (warnEmptyRegistry) {
            Logger.warn(
                "setConsent called before registerConsentCategories — optOut stored as-is with no " +
                    "reconciliation. Register categories before initialize."
            )
        }
        persist(pending)
        return true
    }

    /**
     * Wipes memory and persistence, watermark included, returning to the implicit
     * default. Distinct from `reset()`, which clears identity and deliberately leaves
     * consent alone.
     *
     * The watermark goes too. Keeping it rejected the CMP re-pushing its cached choice
     * after a logout clear, and with an opt-in-default category that left a user who
     * rejected everything opted in. A host that re-pushes a snapshot from before the
     * clear gets that snapshot applied.
     */
    fun clearConsent() {
        val pending = synchronized(lock) { commitLocked(null) }
        persist(pending)
    }

    private class PendingPersist(val sequence: Long, val value: StoredConsent?)

    /**
     * The one write path for stored state. Caller holds [lock] and must hand the
     * returned value to [persist] after releasing it.
     */
    private fun commitLocked(newStored: StoredConsent?): PendingPersist {
        val before = visibleLocked()
        stored = newStored
        reconciled = newStored?.let { ConsentReconciler.reconcile(it.consent, registry) }
        commitSequence += 1
        notifyIfChangedLocked(before)
        return PendingPersist(commitSequence, newStored)
    }

    /**
     * Writes a commit to storage unless a later commit already has. Two threads can
     * leave the manager lock in the opposite order from their commits; the sequence
     * check keeps disk from ending on an older state than memory.
     */
    private fun persist(pending: PendingPersist) {
        synchronized(persistLock) {
            if (pending.sequence <= persistedSequence) return
            persistedSequence = pending.sequence
            val value = pending.value
            if (value != null) storage.set(value) else storage.clear()
        }
    }

    // ===== Subscriptions =====

    /**
     * Registers a change handler and immediately emits the current value — a late
     * subscriber must not have to guess whether consent already arrived. Then fires on
     * every real change, never on a value-identical `setConsent`.
     *
     * Registration, snapshot, and enqueue happen under the same lock as commits:
     * otherwise a concurrent `setConsent` (which already sees the new subscriber) could
     * enqueue the new value before this initial emission, delivering new-then-old and
     * leaving the subscriber on stale state.
     *
     * @return an unsubscribe function; idempotent. Called from the main thread, it
     *   guarantees the handler is not invoked again, including for deliveries that were
     *   already enqueued (they are cancelled at delivery time). Called from another
     *   thread it can race one in-flight delivery.
     */
    fun subscribe(handler: (Consent) -> Unit): () -> Unit {
        val subscription = Subscription(handler)
        synchronized(lock) {
            subscribers += subscription
            deliverLocked(visibleLocked(), subscription)
        }
        return {
            subscription.cancel()
            synchronized(lock) { subscribers.remove(subscription) }
        }
    }

    /**
     * Caller holds [lock]. Compares against what readers could see before the commit:
     * a set that changes nothing visible — same values, fresher clock, or a first set
     * equal to the implicit default — must not fire. Handlers react to decisions, not
     * clocks.
     */
    private fun notifyIfChangedLocked(before: Consent) {
        val after = visibleLocked()
        if (after.sameDecision(before)) return
        for (subscription in subscribers) {
            deliverLocked(after, subscription)
        }
    }

    /**
     * Caller holds [lock]. Only enqueues — with the production (asynchronous) dispatch
     * the handler runs later off the lock, so a slow or re-entrant handler cannot stall
     * a commit.
     */
    private fun deliverLocked(consent: Consent, subscription: Subscription) {
        dispatch(Runnable { subscription.activeHandler?.invoke(consent) })
    }

    // ===== Test seam =====

    /**
     * Swaps the backing storage and rebuilds state from it, dropping registry and
     * subscribers. For tests that must exercise the process-wide manager without
     * touching the production preferences file.
     */
    fun replaceStorageForTesting(newStorage: ConsentStorage) {
        val loaded = newStorage.get()
        synchronized(persistLock) {
            synchronized(lock) {
                subscribers.forEach(Subscription::cancel)
                subscribers.clear()
                storage = newStorage
                registry = emptyList()
                registrationProblems = emptyList()
                implicitDefault = ConsentReconciler.implicitDefault(emptyList())
                stored = loaded
                reconciled = loaded?.let { ConsentReconciler.reconcile(it.consent, emptyList()) }
                warnedEmptyRegistry = false
                commitSequence = 0
                persistedSequence = 0
            }
        }
    }

    companion object {
        private fun mainLooperDispatch(): (Runnable) -> Unit {
            val mainHandler = Handler(Looper.getMainLooper())
            return { work -> mainHandler.post(work) }
        }

        private fun checkedDispatch(injected: (Runnable) -> Unit): (Runnable) -> Unit = { work ->
            val check = InlineDispatchCheck()
            injected(Runnable { check.assertNotInline(); work.run() })
            check.markReturned()
        }

        /**
         * A host timestamp past this is not epoch milliseconds — micro- or nanoseconds
         * (~1.7e15+) — and would reject every later real timestamp until the next
         * `clearConsent`; it is applied as untimestamped instead, with a warning and
         * no fail-fast: the timestamp usually comes from the CMP SDK, and vendor data
         * must not crash the host. A fixed date, not the device clock, which this rule
         * deliberately never consults. Seconds are not caught: a host that sends one
         * millisecond timestamp and then seconds has every seconds call rejected until
         * it clears. Use one unit.
         */
        const val MAX_PLAUSIBLE_HOST_TIMESTAMP = 4_102_444_800_000L // 2100-01-01 in ms
    }
}
