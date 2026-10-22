package com.metarouter.analytics.consent

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ConsentManager state-machine tests. The clock and the callback dispatcher are
 * injected: subscriber deliveries land in a recorder the test drains explicitly, so
 * ordering is observable and nothing depends on the main looper.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentManagerTest {

    private lateinit var context: Context
    private lateinit var storage: ConsentStorage
    private lateinit var dispatcher: RecordingDispatcher
    private var now = 1_757_400_000_000L

    /**
     * Stands in for the main looper: FIFO, drained on demand, never inside the
     * manager's lock — exactly the production shape minus the thread hop.
     */
    private class RecordingDispatcher {
        private val lock = Any()
        private val pending = ArrayDeque<Runnable>()

        fun enqueue(work: Runnable) = synchronized(lock) { pending.addLast(work) }

        val pendingCount: Int get() = synchronized(lock) { pending.size }

        /** Runs everything queued so far, including work the handlers it runs enqueue, in FIFO order. */
        fun drain() {
            while (true) {
                val work = synchronized(lock) { pending.removeFirstOrNull() } ?: return
                work.run()
            }
        }
    }

    private val registry = listOf(
        ConsentCategory("c0001", defaultOptOut = false),
        ConsentCategory("c0002"),
        ConsentCategory("c0004")
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(context)
        storage.clear()
        dispatcher = RecordingDispatcher()
    }

    @After
    fun tearDown() {
        storage.clear()
    }

    private fun makeManager() = ConsentManager(storage, clockMillis = { now }, dispatch = dispatcher::enqueue)

    private fun subscribeRecording(manager: ConsentManager): Pair<MutableList<Consent>, () -> Unit> {
        val received = CopyOnWriteArrayList<Consent>()
        val unsubscribe = manager.subscribe { received += it }
        return received to unsubscribe
    }

    // ===== Implicit default =====

    @Test
    fun `getConsent before anything is empty implicit default`() {
        assertEquals(Consent(explicit = false, optOut = emptyMap()), makeManager().getConsent())
    }

    @Test
    fun `getConsent after registration is implicit default over registry`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)

        val consent = manager.getConsent()

        assertFalse(consent.explicit)
        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to true), consent.optOut)
        assertNull("the implicit default is served, never persisted", storage.get())
    }

    // ===== setConsent =====

    @Test
    fun `setConsent reconciles and persists`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)

        val accepted = manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false, "junk" to true)))

        assertTrue(accepted)
        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to true), manager.getConsent().optOut)
        assertTrue(manager.getConsent().explicit)
        assertEquals(
            "storage keeps the host's raw snapshot — reconciliation is a view, recomputed per registry",
            mapOf("c0002" to false, "junk" to true),
            storage.get()?.consent?.optOut
        )
    }

    @Test
    fun `setConsent stamps clock when timestamp absent`() {
        val manager = makeManager()
        now = 123_456

        manager.setConsent(Consent(explicit = true, optOut = emptyMap()))

        assertEquals(123_456L, manager.getConsent().timestamp)
        assertEquals(123_456L, storage.get()?.consent?.timestamp)
        assertNull(storage.get()?.lastHostTimestamp)
    }

    @Test
    fun `setConsent keeps caller timestamp`() {
        val manager = makeManager()

        manager.setConsent(Consent(explicit = true, optOut = emptyMap(), timestamp = 99))

        assertEquals(99L, manager.getConsent().timestamp)
    }

    @Test
    fun `setConsent replaces not merges`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true, "b" to true), timestamp = 1))

        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 2))

        assertEquals(mapOf("a" to false), manager.getConsent().optOut)
    }

    @Test
    fun `stale setConsent is ignored`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false), timestamp = 2_000))
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()
        val before = manager.getConsent()

        val accepted = manager.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to true), timestamp = 1_000))
        dispatcher.drain()

        assertFalse(accepted)
        assertEquals("older snapshot must not overwrite a newer one", before, manager.getConsent())
        assertEquals(mapOf("c0004" to false), storage.get()?.consent?.optOut)
        assertEquals(2_000L, storage.get()?.lastHostTimestamp)
        assertEquals("only the initial emission — a rejected set never notifies", 1, received.size)
    }

    @Test
    fun `equal timestamp is accepted`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 5))

        val accepted = manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 5))

        assertTrue(accepted)
        assertEquals(mapOf("a" to false), manager.getConsent().optOut)
    }

    /**
     * Wall clock went backwards (NTP, user reset the date) after a host-timestamped
     * snapshot was stored. A fresh untimestamped set is the user's current choice and
     * must still win — rejecting it would drop an opt-out.
     */
    @Test
    fun `untimestamped set is never rejected as stale when the clock moved back`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 500))

        now = 400
        assertTrue(manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true))))

        assertEquals(mapOf("a" to true), manager.getConsent().optOut)
        assertEquals("device clock, informational — not clamped", 400L, manager.getConsent().timestamp)
    }

    /**
     * The host watermark survives an untimestamped set. Otherwise a host re-pushing the
     * same decision without a timestamp (foreground refresh) would let an older CMP
     * replay overwrite the user's newer explicit choice.
     */
    @Test
    fun `untimestamped re-push does not reset the host watermark`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 2_000)) // banner choice
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true))) // same decision, no timestamp
        assertEquals("watermark carried forward", 2_000L, storage.get()?.lastHostTimestamp)

        val accepted = manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 1_000)) // cached replay

        assertFalse(accepted)
        assertEquals("the newer explicit choice stands", mapOf("a" to true), manager.getConsent().optOut)
        assertTrue(
            "a newer host snapshot still wins",
            manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 2_001))
        )
    }

    /**
     * Equal is not older: a replay carrying the watermark's exact timestamp applies,
     * even after an untimestamped choice. A newer host snapshot moves the watermark,
     * after which only the older one is stale.
     */
    @Test
    fun `watermark moves with each newer host snapshot`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 1_000)) // T1
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true))) // untimestamped
        assertTrue(manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 2_000))) // T2

        assertTrue("replay of T2", manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 2_000)))
        assertEquals(mapOf("a" to false), manager.getConsent().optOut)
        assertFalse("T1 is now stale", manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 1_000)))
        assertEquals(2_000L, storage.get()?.lastHostTimestamp)
    }

    /**
     * The failure this rule exists for: the host applies an untimestamped regional
     * default (stamped by a device clock running fast), then the banner callback
     * arrives carrying the CMP's server time, which is behind the device. The explicit
     * choice must win.
     */
    @Test
    fun `banner choice with server time is not rejected after a device-stamped default`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        now = 1_757_400_600_000 // device clock, 10 min fast
        manager.setConsent(Consent(explicit = false, optOut = mapOf("c0002" to true, "c0004" to true)))
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        val accepted = manager.setConsent(
            Consent(explicit = true, optOut = mapOf("c0002" to false, "c0004" to false), timestamp = 1_757_400_000_000) // server time
        )
        dispatcher.drain()

        assertTrue(accepted)
        assertTrue(manager.getConsent().explicit)
        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to false), manager.getConsent().optOut)
        assertEquals("subscribers hear the explicit choice", 2, received.size)
    }

    // ===== Registry =====

    @Test
    fun `setConsent before registration passes through and later registration completes it`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false, "junk" to true), timestamp = 1))
        assertEquals("empty registry → as-is", mapOf("c0002" to false, "junk" to true), manager.getConsent().optOut)
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.registerConsentCategories(registry)
        dispatcher.drain()

        val expected = mapOf("c0001" to false, "c0002" to false, "c0004" to true)
        assertEquals(expected, manager.getConsent().optOut)
        assertTrue("explicit carried through", manager.getConsent().explicit)
        assertEquals("re-reconciliation is not a new decision", 1L, manager.getConsent().timestamp)
        assertEquals("raw snapshot stays on disk", mapOf("c0002" to false, "junk" to true), storage.get()?.consent?.optOut)
        assertEquals(
            "subscribers learn the completed block",
            listOf(mapOf("c0002" to false, "junk" to true), expected),
            received.map { it.optOut }
        )
    }

    @Test
    fun `re-registration replaces not merges`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)

        manager.registerConsentCategories(listOf(ConsentCategory("analytics", defaultOptOut = false)))

        assertEquals(mapOf("analytics" to false), manager.getConsent().optOut)
    }

    @Test
    fun `registration that changes nothing does not notify`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to false)))
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.registerConsentCategories(registry)
        dispatcher.drain()

        assertEquals(1, received.size)
    }

    @Test
    fun `registration normalizes keys and degrades instead of throwing`() {
        val manager = makeManager()

        manager.registerConsentCategories(
            listOf(
                ConsentCategory(" C0001 ", defaultOptOut = false),
                ConsentCategory(""),
                ConsentCategory("c0002"),
                ConsentCategory("C0002", defaultOptOut = false)
            )
        )

        assertEquals(
            "lowercased + trimmed, blank dropped, duplicate keeps first",
            mapOf("c0001" to false, "c0002" to true),
            manager.getConsent().optOut
        )
        assertEquals("one problem each: casing ×2, blank, duplicate", 4, manager.registrationProblems().size)

        manager.registerConsentCategories(registry)
        assertTrue("a clean registration clears them", manager.registrationProblems().isEmpty())
    }

    /** A Java host can call put() on any Map; the one handed out must refuse it. */
    @Test
    fun `consent handed out is read-only`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))

        for (consent in listOf(manager.getConsent(), makeManager().apply { clearConsent() }.getConsent())) {
            try {
                @Suppress("UNCHECKED_CAST")
                (consent.optOut as MutableMap<String, Boolean>)["c0004"] = false
                fail("optOut must not be writable")
            } catch (expected: UnsupportedOperationException) {
            }
        }
        assertEquals(true, manager.getConsent().optOut["c0004"])
    }

    /** The host's map is copied: mutating it afterwards changes nothing the SDK holds. */
    @Test
    fun `host mutating the map it passed does not change stored consent`() {
        val manager = makeManager()
        val hostMap = mutableMapOf("c0002" to false)
        manager.setConsent(Consent(explicit = true, optOut = hostMap))

        hostMap["c0002"] = true
        hostMap["c0004"] = false
        manager.registerConsentCategories(registry)

        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to true), manager.getConsent().optOut)
        assertEquals(mapOf("c0002" to false), storage.get()?.consent?.optOut)
    }

    /** Java hosts get the defaulted constructors. */
    @Test
    fun `consent types expose Java overloads`() {
        Consent::class.java.getConstructor(Boolean::class.javaPrimitiveType, Map::class.java)
        ConsentCategory::class.java.getConstructor(String::class.java)
    }

    // ===== Persistence across relaunch =====

    @Test
    fun `consent survives relaunch`() {
        val first = makeManager()
        first.registerConsentCategories(registry)
        first.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 10))

        val relaunched = makeManager()
        relaunched.registerConsentCategories(registry)

        assertEquals(
            Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to true), timestamp = 10),
            relaunched.getConsent()
        )
    }

    /**
     * An app update that registers a new category must not ship events missing that
     * key — a missing key is not read as blocked downstream. The stored decision stays
     * intact for the categories it covered; the new one takes its registered default
     * until the user is asked again.
     */
    @Test
    fun `relaunch with new category completes stored state`() {
        val first = makeManager()
        first.registerConsentCategories(registry)
        first.setConsent(
            Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to false), timestamp = 10)
        )

        val updated = makeManager()
        val (received, _) = subscribeRecording(updated)
        dispatcher.drain()
        updated.registerConsentCategories(registry + ConsentCategory("c0005"))
        dispatcher.drain()

        val expected = mapOf("c0001" to false, "c0002" to false, "c0004" to false, "c0005" to true)
        assertEquals(expected, updated.getConsent().optOut)
        assertEquals(expected, received.last().optOut)
    }

    @Test
    fun `stale rule holds across relaunch`() {
        makeManager().setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 2_000))

        val relaunched = makeManager()

        assertFalse(relaunched.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 1_000)))
        assertEquals(mapOf("a" to true), relaunched.getConsent().optOut)
    }

    // ===== clearConsent =====

    @Test
    fun `clearConsent resets to implicit default and wipes storage`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.clearConsent()
        dispatcher.drain()

        val implicitDefault = Consent(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true))
        assertEquals(implicitDefault, manager.getConsent())
        assertNull(storage.get())
        assertEquals(2, received.size)
        assertEquals(implicitDefault, received.last())
    }

    @Test
    fun `clearConsent when nothing visible changes does not notify`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.clearConsent()
        dispatcher.drain()

        assertEquals("never set → clear is a visible no-op", 1, received.size)
    }

    /**
     * A logout clear followed by the CMP re-pushing its cached reject-all on the next
     * launch, with an opt-in-default category registered. Were the watermark kept, the
     * replay would be rejected and `c0001` would fall back to its opted-in default for
     * a user who rejected everything.
     */
    @Test
    fun `replay after clear is applied so an opt-in default cannot opt the user in`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        val rejectAll = Consent(explicit = true, optOut = mapOf("c0001" to true, "c0002" to true, "c0004" to true), timestamp = 2_000)
        manager.setConsent(rejectAll)
        manager.clearConsent()
        assertNull("clear wipes the watermark with the consent", storage.get())
        assertEquals("implicit default: c0001 opted in", false, manager.getConsent().optOut["c0001"])

        val relaunched = makeManager()
        relaunched.registerConsentCategories(registry)

        assertTrue(relaunched.setConsent(rejectAll))
        assertEquals(mapOf("c0001" to true, "c0002" to true, "c0004" to true), relaunched.getConsent().optOut)
    }

    /**
     * A wrong-unit timestamp (here microseconds) must not become the watermark — every
     * later real timestamp would lose until the next clear. Vendor data: warns, never
     * throws.
     */
    @Test
    fun `timestamp too large for milliseconds is applied as untimestamped`() {
        val manager = makeManager()
        now = 7_000
        assertTrue(manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 1_757_400_000_000_000)))

        assertEquals("stamped by the device clock, like an untimestamped call", 7_000L, manager.getConsent().timestamp)
        assertNull("never became the watermark", storage.get()?.lastHostTimestamp)
        assertTrue(
            "a real millisecond timestamp still applies",
            manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 1_757_400_000_000))
        )
    }

    @Test
    fun `fresh choice after withdrawal is accepted`() {
        val manager = makeManager()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 2_000))
        manager.clearConsent()

        assertTrue(
            "older than the cleared watermark",
            manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 1_500))
        )
        manager.clearConsent()
        assertTrue("untimestamped is never rejected", manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false))))
        assertEquals(mapOf("a" to false), manager.getConsent().optOut)
    }

    // ===== subscribe =====

    @Test
    fun `subscribe emits current value immediately`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))

        val (received, _) = subscribeRecording(manager)
        assertEquals("delivery is asynchronous, never inline on the subscribing thread", 0, received.size)
        dispatcher.drain()

        assertEquals(listOf(manager.getConsent()), received)
    }

    @Test
    fun `subscribe fires on change not on no-op set`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 1))
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 2)) // same decision, fresher clock
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to true), timestamp = 3))
        dispatcher.drain()

        assertEquals("initial + two real changes", 3, received.size)
        assertEquals(false, received[1].optOut["c0002"])
        assertEquals(true, received[2].optOut["c0002"])
        assertEquals(3L, manager.getConsent().timestamp)
    }

    @Test
    fun `first set equal to implicit default does not fire`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.setConsent(Consent(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true)))
        dispatcher.drain()

        assertEquals("nothing a subscriber can see changed", 1, received.size)
        assertNotNull("but the host's set is still recorded", storage.get())
    }

    @Test
    fun `unsubscribe stops delivery and is idempotent`() {
        val manager = makeManager()
        val (received, unsubscribe) = subscribeRecording(manager)
        dispatcher.drain()

        unsubscribe()
        unsubscribe()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true)))
        dispatcher.drain()

        assertEquals(1, received.size)
    }

    @Test
    fun `multiple subscribers each receive changes`() {
        val manager = makeManager()
        val (a, _) = subscribeRecording(manager)
        val (b, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true)))
        dispatcher.drain()

        assertEquals(2, a.size)
        assertEquals(2, b.size)
    }

    /**
     * Enqueue-inside-the-lock is the ordering guarantee: under concurrent sets a
     * subscriber must see commits in commit order, and its last delivery must be the
     * state `getConsent()` reports.
     */
    @Test
    fun `delivery order matches commit order under concurrent sets`() {
        val manager = makeManager()
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(64) { i ->
            pool.execute {
                start.await()
                manager.setConsent(Consent(explicit = true, optOut = mapOf("n" to (i % 2 == 0)), timestamp = i.toLong()))
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        dispatcher.drain()

        val timestamps = received.drop(1).mapNotNull { it.timestamp }
        assertEquals("deliveries never go backwards in time", timestamps.sorted(), timestamps)
        // Same decision, not equal: a later no-op set (same values, newer clock) moves
        // the timestamp without notifying.
        assertTrue("the last delivery is the current decision", received.last().sameDecision(manager.getConsent()))
        assertEquals(63L, manager.getConsent().timestamp)
    }

    /** The production dispatcher: handlers land on the main thread. */
    @Test
    fun `default dispatcher delivers on main thread`() {
        val manager = ConsentManager(storage)
        val onMain = CopyOnWriteArrayList<Boolean>()

        val unsubscribe = manager.subscribe { onMain += (Looper.myLooper() == Looper.getMainLooper()) }
        assertTrue("posted, not run inline", onMain.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        unsubscribe()

        assertEquals(listOf(true), onMain)
    }

    /** A handler may call back into the manager — it runs off the lock. */
    @Test
    fun `handler may reenter manager`() {
        val manager = makeManager()
        val seen = CopyOnWriteArrayList<Consent>()
        manager.subscribe { seen += manager.getConsent() }

        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true)))
        dispatcher.drain()

        assertEquals(2, seen.size)
    }

    /** An injected dispatcher that runs work inline would run handlers under the lock. */
    @Test
    fun `inline dispatcher is rejected`() {
        val manager = ConsentManager(storage, clockMillis = { now }, dispatch = { it.run() })

        try {
            manager.subscribe { }
            fail("an inline dispatcher must throw")
        } catch (expected: IllegalStateException) {
        }
    }

    // ===== Review follow-ups =====

    /**
     * A subscriber attached before registration (nothing stored) must hear the implicit
     * default change when the registry arrives — registration is a commit like any other.
     */
    @Test
    fun `registration with nothing stored notifies subscribers of the new implicit default`() {
        val manager = makeManager()
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()
        assertEquals(listOf(Consent(explicit = false, optOut = emptyMap())), received)

        manager.registerConsentCategories(registry)
        dispatcher.drain()

        assertEquals(Consent(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true)), received.last())
        assertEquals(2, received.size)
    }

    @Test
    fun `registration after clear notifies when the implicit default changes`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))
        manager.clearConsent()
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.registerConsentCategories(listOf(ConsentCategory("analytics", defaultOptOut = false)))
        dispatcher.drain()

        assertEquals(mapOf("analytics" to false), received.last().optOut)
        assertEquals(2, received.size)
    }

    /**
     * Storage holds the raw snapshot, so a category one registration drops and a later
     * one re-adds comes back with the user's decision — never a default the user did
     * not choose.
     */
    @Test
    fun `re-registration never loses the user's decision for dropped keys`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(
            Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to false), timestamp = 1)
        )

        manager.registerConsentCategories(listOf(ConsentCategory("c0001", defaultOptOut = false), ConsentCategory("c0002")))
        assertEquals("view narrows", mapOf("c0001" to false, "c0002" to false), manager.getConsent().optOut)
        assertEquals(
            "storage does not narrow",
            mapOf("c0001" to false, "c0002" to false, "c0004" to false),
            storage.get()?.consent?.optOut
        )

        manager.registerConsentCategories(registry)
        assertEquals("the user's opt-in survives the round trip", false, manager.getConsent().optOut["c0004"])

        val relaunched = makeManager()
        relaunched.registerConsentCategories(registry)
        assertEquals("and survives a relaunch", false, relaunched.getConsent().optOut["c0004"])
    }

    /**
     * Unsubscribing must also cancel deliveries that are already queued: a host that
     * unsubscribes in onDestroy must not get one more callback on the next looper turn.
     */
    @Test
    fun `unsubscribe cancels already queued deliveries`() {
        val manager = makeManager()
        val received = CopyOnWriteArrayList<Consent>()

        val unsubscribe = manager.subscribe { received += it } // initial emission queued
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true))) // change queued
        assertEquals(2, dispatcher.pendingCount)
        unsubscribe()
        dispatcher.drain()

        assertEquals("nothing delivered after unsubscribe returned", 0, received.size)
    }

    /** A handler that unsubscribes itself mid-delivery stops receiving; other subscribers are unaffected. */
    @Test
    fun `handler may unsubscribe itself during delivery`() {
        val manager = makeManager()
        val received = CopyOnWriteArrayList<Consent>()
        var unsubscribe: (() -> Unit)? = null
        unsubscribe = manager.subscribe { consent ->
            received += consent
            if (consent.optOut["a"] == true) unsubscribe?.invoke()
        }
        val (other, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true)))
        dispatcher.drain()
        manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false)))
        dispatcher.drain()

        assertEquals("initial + the change it unsubscribed in", 2, received.size)
        assertEquals(3, other.size)
    }

    /** Registering an empty set after a non-empty one drops back to raw passthrough, and subscribers hear it. */
    @Test
    fun `empty registration after a non-empty one falls back to passthrough`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false, "junk" to true)))
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        manager.registerConsentCategories(emptyList())
        dispatcher.drain()

        assertEquals(mapOf("c0002" to false, "junk" to true), manager.getConsent().optOut)
        assertEquals(mapOf("c0002" to false, "junk" to true), received.last().optOut)
        assertEquals(2, received.size)

        manager.clearConsent()
        assertEquals("empty implicit default", Consent(explicit = false, optOut = emptyMap()), manager.getConsent())
    }

    @Test
    fun `replaceStorageForTesting rebuilds state and drops subscribers`() {
        val manager = makeManager()
        manager.registerConsentCategories(registry)
        manager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))
        val (received, _) = subscribeRecording(manager)
        dispatcher.drain()

        val other = ConsentStorage(context, prefsName = "com.metarouter.analytics.consent.test-other")
        other.set(StoredConsent(Consent(explicit = true, optOut = mapOf("z" to true), timestamp = 3), lastHostTimestamp = 3))
        try {
            manager.replaceStorageForTesting(other)
            manager.setConsent(Consent(explicit = true, optOut = mapOf("y" to true)))
            dispatcher.drain()

            assertEquals("empty registry → raw passthrough from the new storage", mapOf("y" to true), manager.getConsent().optOut)
            assertEquals("old subscriber was dropped", 1, received.size)
            assertNull("old storage untouched", storage.get()?.consent?.optOut?.get("y"))
        } finally {
            other.clear()
        }
    }

    /**
     * Persistence runs off the manager lock: a host listening for preference changes
     * and reading consent back from inside the callback must not see a half-applied
     * commit, and a nested setConsent from there must persist in order (last commit
     * wins on disk). Robolectric runs the test on the main looper, where `apply()`
     * notifies listeners synchronously — the re-entrant shape.
     */
    @Test
    fun `preference listener may reenter the manager during persist`() {
        val manager = makeManager()
        val observed = CopyOnWriteArrayList<Consent>()
        val prefs = context.getSharedPreferences("com.metarouter.analytics.consent", Context.MODE_PRIVATE)
        // Held in a local for the test's duration: SharedPreferences keeps listeners weakly.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            observed += manager.getConsent()
            if (manager.getConsent().optOut["a"] == true) {
                manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to false), timestamp = 2))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            manager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 1))

            assertEquals(mapOf("a" to false), manager.getConsent().optOut)
            assertEquals("disk ends on the latest commit", mapOf("a" to false), storage.get()?.consent?.optOut)
            assertTrue(observed.isNotEmpty())
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
