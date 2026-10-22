package com.metarouter.analytics.consent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.AnalyticsInterface
import com.metarouter.analytics.AnalyticsProxy
import com.metarouter.analytics.consent.adapters.OneTrustAdapter
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Consent through the proxy acts on the shared manager directly — never queued, never
 * dependent on a bind, never forwarded to the bound client (which stamps from that
 * same manager).
 */
@RunWith(RobolectricTestRunner::class)
class ConsentProxyTest {

    private lateinit var storage: ConsentStorage
    private lateinit var pending: ArrayDeque<Runnable>
    private lateinit var manager: ConsentManager
    private lateinit var proxy: AnalyticsProxy
    private lateinit var mockClient: AnalyticsInterface

    private val registry = listOf(ConsentCategory("c0001", defaultOptOut = false), ConsentCategory("c0002"))

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(context).apply { clear() }
        pending = ArrayDeque()
        manager = ConsentManager(storage, dispatch = { pending.addLast(it) })
        proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, manager)
        mockClient = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        storage.clear()
        unmockkAll()
    }

    private fun drain() {
        while (true) (pending.removeFirstOrNull() ?: return).run()
    }

    // ===== Before any bind =====

    @Test
    fun `consent is fully usable before bind`() {
        proxy.registerConsentCategories(registry)
        assertEquals(Consent(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true)), proxy.getConsent())

        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 1))

        assertEquals(Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false), timestamp = 1), proxy.getConsent())
        assertEquals("persisted immediately — nothing waits for a bind", mapOf("c0002" to false), storage.get()?.consent?.optOut)
        assertEquals("never queued", 0, proxy.pendingCallCount())
    }

    @Test
    fun `subscribe before bind emits current and then changes`() {
        proxy.registerConsentCategories(registry)
        val received = CopyOnWriteArrayList<Consent>()

        val subscription = proxy.subscribeToConsent { received += it }
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0001" to true)))
        drain()
        subscription.close()
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0001" to false)))
        drain()

        assertEquals(
            listOf(mapOf("c0001" to false, "c0002" to true), mapOf("c0001" to true, "c0002" to true)),
            received.map { it.optOut }
        )
    }

    // ===== Public surface =====

    @Test
    fun `stale setConsent returns false and leaves state alone`() {
        proxy.registerConsentCategories(registry)
        assertTrue(proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 2_000)))

        assertFalse(proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to true), timestamp = 1_000)))
        assertEquals(false, proxy.getConsent().optOut["c0002"])
    }

    @Test
    fun `value-identical setConsent does not notify`() {
        proxy.registerConsentCategories(registry)
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 1))
        val received = CopyOnWriteArrayList<Consent>()
        proxy.subscribeToConsent { received += it }.use {
            drain()

            proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 2))
            drain()

            assertEquals("only the initial emission", 1, received.size)
        }
    }

    @Test
    fun `subscription reports closing`() {
        val subscription = proxy.subscribeToConsent { }
        assertFalse(subscription.isClosed)

        subscription.close()
        subscription.close()

        assertTrue(subscription.isClosed)
    }

    /** A cookie carrying both casings of one group resolves opted-out end to end. */
    @Test
    fun `mixed-case groups collision through setConsent resolves opted out`() {
        proxy.registerConsentCategories(listOf(ConsentCategory("c0004", defaultOptOut = false)))

        proxy.setConsent(OneTrustAdapter.normalize(groups = "C0004:1,c0004:0", explicit = true))

        assertEquals(mapOf("c0004" to true), proxy.getConsent().optOut)
    }

    @Test
    fun `clearConsent before bind`() {
        proxy.registerConsentCategories(registry)
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0001" to true)))

        proxy.clearConsent()

        assertEquals(Consent(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true)), proxy.getConsent())
        assertNull(storage.get())
    }

    @Test
    fun `pre-bind debug info carries consent`() = runTest {
        proxy.registerConsentCategories(registry)

        val info = proxy.getDebugInfo()

        assertEquals(false, info["bound"])
        assertEquals(mapOf("explicit" to false, "optOut" to mapOf("c0001" to false, "c0002" to true)), info["consent"])
    }

    // ===== Across bind =====

    /**
     * Consent is never replayed or forwarded: the bound client reads the same manager,
     * so forwarding would be a second write of the same state at best and, through the
     * queue, a lost or reordered one at worst.
     */
    @Test
    fun `bound proxy still acts on the manager and never forwards to the client`() = runTest {
        proxy.bind(mockClient)

        proxy.registerConsentCategories(registry)
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))
        proxy.getConsent()
        proxy.subscribeToConsent { }.close()
        proxy.clearConsent()

        verify(exactly = 0) {
            mockClient.registerConsentCategories(any())
            mockClient.setConsent(any())
            mockClient.getConsent()
            mockClient.subscribeToConsent(any())
            mockClient.clearConsent()
        }
        assertEquals(Consent(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true)), manager.getConsent())
    }

    @Test
    fun `consent set before bind is still there after bind and after unbind`() = runTest {
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 9))

        proxy.bind(mockClient)
        assertEquals(mapOf("a" to true), proxy.getConsent().optOut)

        proxy.unbind()
        assertEquals("unbind (reset) must not touch consent", mapOf("a" to true), proxy.getConsent().optOut)
    }

    /** Unlike the queued calls, consent does not have to fit in the pending-call cap. */
    @Test
    fun `consent is not subject to the pending queue cap`() {
        val small = AnalyticsProxy(maxPendingCalls = 5, consentManager = manager)
        repeat(40) { small.track("filler $it") }

        small.setConsent(Consent(explicit = true, optOut = mapOf("a" to true)))

        assertEquals(mapOf("a" to true), small.getConsent().optOut)
        assertEquals(5, small.pendingCallCount())
    }
}
