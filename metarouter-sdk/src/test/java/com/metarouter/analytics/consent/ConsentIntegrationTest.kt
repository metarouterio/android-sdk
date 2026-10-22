package com.metarouter.analytics.consent

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.AnalyticsProxy
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.MetaRouter
import com.metarouter.analytics.MetaRouterAnalyticsClient
import com.metarouter.analytics.MetaRouterInitializer
import com.metarouter.analytics.consent.adapters.OneTrustAdapter
import com.metarouter.analytics.queue.EventQueueInterface
import com.metarouter.analytics.types.ConsentContext
import com.metarouter.analytics.types.EnrichedEventPayload
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end consent behavior: through a real client (events captured at the queue)
 * and through the MetaRouter entry point (the process-wide manager shared by proxy and
 * client, attached to storage at process start or initialize, surviving reset).
 */
@RunWith(RobolectricTestRunner::class)
class ConsentIntegrationTest {

    private lateinit var appContext: Context
    private lateinit var storage: ConsentStorage
    private lateinit var queue: RecordingEventQueue

    private val registry = listOf(
        ConsentCategory("c0001", defaultOptOut = false),
        ConsentCategory("c0002"),
        ConsentCategory("c0004")
    )

    private val options = InitOptions(
        writeKey = "wk",
        ingestionHost = "https://events.example.com",
        flushIntervalSeconds = 999,
        trackLifecycleEvents = false
    )

    /** Records enqueued events and never lets the dispatcher drain them away. */
    private class RecordingEventQueue : EventQueueInterface {
        val events = CopyOnWriteArrayList<EnrichedEventPayload>()
        override fun size(): Int = events.size
        override fun enqueue(event: EnrichedEventPayload) { events.add(event) }
        override fun drain(max: Int): List<EnrichedEventPayload> = emptyList()
        override fun requeueToFront(events: List<EnrichedEventPayload>) {}
        override fun clear() { events.clear() }
    }

    @Before
    fun setup() = runBlocking {
        appContext = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(appContext).apply { clear() }
        queue = RecordingEventQueue()
        MetaRouter.resetForTesting()
        // The process-wide manager outlives tests: another test's initialize may have
        // attached storage already.
        MetaRouter.consentManagerForTesting.replaceStorageForTesting(null)
    }

    @After
    fun tearDown() = runBlocking {
        MetaRouter.resetForTesting()
        MetaRouter.consentManagerForTesting.replaceStorageForTesting(null)
        storage.clear()
        unmockkAll()
    }

    private fun mockContext(debuggable: Boolean = false): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        if (debuggable) {
            every { context.applicationInfo } returns ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
        }
        return context
    }

    private suspend fun awaitEvent(name: String): EnrichedEventPayload {
        val start = System.currentTimeMillis()
        while (true) {
            queue.events.firstOrNull { it.event == name }?.let { return it }
            if (System.currentTimeMillis() - start > 2000) throw AssertionError("$name was never enqueued")
            delay(20)
        }
    }

    // ===== Payload shape through the real client =====

    private suspend fun makeClient(manager: ConsentManager) = MetaRouterAnalyticsClient.initialize(
        mockContext(), options, eventQueue = queue, consentManager = manager
    )

    @Test
    fun `event before any set carries implicit default`() = runBlocking {
        val client = makeClient(ConsentManager(storage, dispatch = { }))
        client.registerConsentCategories(registry)

        client.track("Before")

        assertEquals(
            ConsentContext(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true)),
            awaitEvent("Before").context.consent
        )
    }

    /**
     * Pre-bind calls are queued and enriched at bind, so a queued event carries the
     * consent current at bind — latest wins — not the consent at its call.
     */
    @Test
    fun `pre-bind event is stamped with the consent current at bind`() = runBlocking {
        val manager = ConsentManager(storage, dispatch = { })
        val client = makeClient(manager)
        val proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, manager)
        proxy.registerConsentCategories(registry)
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false)))

        proxy.track("Queued")
        proxy.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to true)))
        proxy.bind(client)

        assertEquals(true, awaitEvent("Queued").context.consent?.optOut?.get("c0004"))
    }

    @Test
    fun `setConsent then event carries reconciled consent`() = runBlocking {
        val client = makeClient(ConsentManager(storage, dispatch = { }))
        client.registerConsentCategories(registry)

        client.setConsent(OneTrustAdapter.normalize(mapOf("C0002" to 1, "C0004" to 0, "C0099" to 1), explicit = true))
        client.track("After")

        assertEquals(
            ConsentContext(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to true)),
            awaitEvent("After").context.consent
        )
    }

    @Test
    fun `clearConsent then event carries implicit default again`() = runBlocking {
        val client = makeClient(ConsentManager(storage, dispatch = { }))
        client.registerConsentCategories(registry)
        client.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false, "c0004" to false)))
        client.track("Consented")
        awaitEvent("Consented")

        client.clearConsent()
        client.track("Cleared")

        assertEquals(
            ConsentContext(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true)),
            awaitEvent("Cleared").context.consent
        )
    }

    // ===== reset() vs clearConsent() =====

    @Test
    fun `reset leaves consent intact`() = runBlocking {
        val client = makeClient(ConsentManager(storage, dispatch = { }))
        client.registerConsentCategories(registry)
        client.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false), timestamp = 7))

        client.reset()

        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to true), client.getConsent().optOut)
        assertEquals("still persisted", 7L, storage.get()?.consent?.timestamp)
    }

    @Test
    fun `debug info carries consent`() = runBlocking {
        val client = makeClient(ConsentManager(storage, dispatch = { }))
        client.registerConsentCategories(registry)
        client.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))

        val info = client.getDebugInfo()

        assertEquals(
            mapOf("explicit" to true, "optOut" to mapOf("c0001" to false, "c0002" to false, "c0004" to true)),
            info["consent"]
        )
    }

    // ===== Through MetaRouter: one manager for proxy and client =====

    @Test
    fun `consent set before initialize is seen by the initialized client and survives reset`() = runBlocking {
        MetaRouter.consentManagerForTesting.replaceStorageForTesting(storage)
        val shared = MetaRouter.Analytics.shared
        shared.registerConsentCategories(registry)
        shared.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false)))
        val expected = Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to false))

        val client = MetaRouter.initializeAndWait(mockContext(), options)
        assertTrue("read after bind", client.getConsent().sameDecision(expected))

        val debug = client.getDebugInfo()
        assertEquals("debug info comes from the real client…", true, debug["bound"])
        assertEquals(
            "…which stamps from the same manager the pre-init set landed on",
            mapOf("explicit" to true, "optOut" to mapOf("c0001" to false, "c0002" to true, "c0004" to false)),
            debug["consent"]
        )

        MetaRouter.Analytics.resetAndWait()
        assertTrue("reset clears identity, never consent", MetaRouter.Analytics.shared.getConsent().sameDecision(expected))

        val reinitialized = MetaRouter.initializeAndWait(mockContext(), options)
        assertTrue("re-initialized client sees it too", reinitialized.getConsent().sameDecision(expected))
    }

    /**
     * The fallback path, when a host removed the startup initializer: storage attaches
     * at initialize. A previous launch's consent loads then, and a pre-initialize CMP
     * replay older than it loses there — the same outcome as iOS, where storage exists
     * from process start.
     */
    @Test
    fun `initialize loads persisted consent and replays pre-initialize writes over it`() = runBlocking {
        storage.set(StoredConsent(Consent(explicit = true, optOut = mapOf("c0004" to true), timestamp = 2_000), lastHostTimestamp = 2_000))
        val shared = MetaRouter.Analytics.shared
        shared.registerConsentCategories(registry)
        assertEquals("not loaded before initialize", false, shared.getConsent().explicit)
        shared.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false), timestamp = 1_000)) // stale replay

        MetaRouter.initializeAndWait(appContext, options)

        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to true), shared.getConsent().optOut)
        assertEquals("the persisted, newer choice stands on disk", 2_000L, storage.get()?.lastHostTimestamp)
    }

    @Test
    fun `debuggable build fails fast at initialize on a degraded registration`() {
        MetaRouter.consentManagerForTesting.replaceStorageForTesting(storage)
        MetaRouter.Analytics.shared.registerConsentCategories(listOf(ConsentCategory("C0004")))

        try {
            MetaRouter.createAnalyticsClient(mockContext(debuggable = true), options)
            fail("a degraded registration must throw on a debuggable build")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("C0004"))
        }
    }

    @Test
    fun `release build degrades a bad registration with a warning`() = runBlocking {
        MetaRouter.consentManagerForTesting.replaceStorageForTesting(storage)
        MetaRouter.Analytics.shared.registerConsentCategories(listOf(ConsentCategory("C0004")))

        MetaRouter.initializeAndWait(mockContext(), options)

        assertEquals(mapOf("c0004" to true), MetaRouter.Analytics.shared.getConsent().optOut)
    }

    /** `shared` is the pre-initialize handle; `client()` keeps throwing until initialize. */
    @Test
    fun `shared is usable before initialize`() {
        val shared = MetaRouter.Analytics.shared
        shared.track("queued before initialize")
        assertTrue(shared.setConsent(Consent(explicit = true, optOut = mapOf("a" to true))))

        try {
            MetaRouter.Analytics.client()
            fail("client() still requires initialize")
        } catch (expected: IllegalStateException) {
        }
    }

    // ===== Startup initializer =====

    /**
     * With the startup initializer, consent works before initialize exactly as on iOS: the
     * previous launch's state is readable, and a write reaches disk at once — so a
     * process that never initializes still keeps a withdrawal.
     */
    @Test
    fun `startup initializer makes consent persistent and readable before initialize`() {
        storage.set(StoredConsent(Consent(explicit = true, optOut = mapOf("c0004" to true), timestamp = 2_000), lastHostTimestamp = 2_000))

        MetaRouterInitializer().create(appContext)
        val shared = MetaRouter.Analytics.shared

        assertEquals("previous launch readable before initialize", mapOf("c0004" to true), shared.getConsent().optOut)
        assertFalse("stale replay rejected at the call", shared.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false), timestamp = 1_000)))
        shared.clearConsent()
        assertNull("written through without initialize", storage.get())
    }

    @Test
    fun `on a debuggable build a registration problem throws at the call after process start`() {
        val context = mockContext(debuggable = true)
        every { context.getSharedPreferences(any(), any()) } answers {
            appContext.getSharedPreferences(firstArg(), secondArg())
        }
        MetaRouter.onProcessStart(context)

        try {
            MetaRouter.Analytics.shared.registerConsentCategories(listOf(ConsentCategory("C0004")))
            fail("must throw at the call, as iOS asserts at the call")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /** Consent is never lifecycle-gated, so neither is its debug info. */
    @Test
    fun `debug info carries consent after the client is reset`() = runBlocking {
        val client = makeClient(ConsentManager(storage, dispatch = { }))
        client.registerConsentCategories(registry)
        client.reset()

        assertEquals(
            mapOf("explicit" to false, "optOut" to mapOf("c0001" to false, "c0002" to true, "c0004" to true)),
            client.getDebugInfo()["consent"]
        )
    }

    /**
     * A client built directly shares the process-wide manager: a second manager over the
     * same preferences file would stamp its own stale copy and overwrite the other's
     * writes on disk.
     */
    @Test
    fun `a directly built client shares the process-wide consent manager`() = runBlocking {
        MetaRouter.consentManagerForTesting.replaceStorageForTesting(storage)
        MetaRouter.Analytics.shared.registerConsentCategories(registry)

        val client = MetaRouterAnalyticsClient.initialize(mockContext(), options)
        MetaRouter.Analytics.shared.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false)))

        assertEquals(false, client.getConsent().optOut["c0004"])
        client.clearConsent()
        assertFalse("one manager: the clear is visible through shared", MetaRouter.Analytics.shared.getConsent().explicit)
    }

    /** One startup step failing must not skip the others. */
    @Test
    fun `a failing storage attach at process start still sets up the registration check`() {
        val context = mockContext(debuggable = true)
        every { context.getSharedPreferences(any(), any()) } throws IllegalStateException("storage unavailable")

        MetaRouter.onProcessStart(context)

        try {
            MetaRouter.Analytics.shared.registerConsentCategories(listOf(ConsentCategory("C0004")))
            fail("the debug check must still be armed")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
