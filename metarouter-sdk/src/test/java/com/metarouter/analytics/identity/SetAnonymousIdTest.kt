package com.metarouter.analytics.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.AnalyticsProxy
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.MetaRouterAnalyticsClient
import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.queue.EventQueueInterface
import com.metarouter.analytics.storage.IdentityStorage
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_ANONYMOUS_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_USER_ID
import com.metarouter.analytics.storage.LifecycleStorage
import com.metarouter.analytics.types.EnrichedEventPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * setAnonymousId: validation, install detection, the pre-attach replay, and reset()
 * ordering. Spec: iOS #70.
 */
@RunWith(RobolectricTestRunner::class)
class SetAnonymousIdTest {

    private lateinit var context: Context
    private lateinit var storage: IdentityStorage
    private lateinit var manager: IdentityManager
    private lateinit var lifecycleStorage: LifecycleStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = IdentityStorage(context).apply { clear() }
        manager = IdentityManager(storage)
        lifecycleStorage = LifecycleStorage(context).apply { clear() }
    }

    @After
    fun tearDown() {
        storage.clear()
        lifecycleStorage.clear()
    }

    // ===== Validation =====

    @Test
    fun `set is read-your-writes`() {
        manager.getAnonymousId()

        assertTrue(manager.setAnonymousId("web-anon-1"))

        assertEquals("web-anon-1", manager.getAnonymousId())
        assertEquals("web-anon-1", storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `value is trimmed`() {
        assertTrue(manager.setAnonymousId("  web-anon-1\n"))
        assertEquals("web-anon-1", manager.getAnonymousId())
    }

    @Test
    fun `blank and oversize values are rejected leaving the id unchanged`() {
        val before = manager.getAnonymousId()

        assertFalse(manager.setAnonymousId(""))
        assertFalse(manager.setAnonymousId(" \t\n"))
        assertFalse(manager.setAnonymousId("a".repeat(257)))
        assertEquals(before, manager.getAnonymousId())
        assertTrue("the limit itself is accepted", manager.setAnonymousId("a".repeat(256)))
    }

    @Test
    fun `setting the current value is a no-op returning true`() {
        assertTrue(manager.setAnonymousId("web-anon-1"))
        storage.clearHostSetMarker()

        assertTrue(manager.setAnonymousId("web-anon-1"))
        assertFalse("an unchanged id writes nothing", storage.hasHostSetMarker())
    }

    // ===== Install detection =====

    @Test
    fun `a host id on a fresh device is not prior identity`() {
        assertTrue(manager.setAnonymousId("web-anon-1"))

        assertTrue(storage.hasHostSetMarker())
        assertFalse(manager.hasAnyValue())
    }

    @Test
    fun `a host id replacing an existing id is still prior identity`() {
        manager.getAnonymousId()

        assertTrue(manager.setAnonymousId("web-anon-1"))

        assertFalse(storage.hasHostSetMarker())
        assertTrue(manager.hasAnyValue())
    }

    @Test
    fun `the host-set marker does not hide other identity`() {
        assertTrue(manager.setAnonymousId("web-anon-1"))
        manager.setUserId("user-1")
        assertTrue(manager.hasAnyValue())
    }

    // ===== Client: cold-launch event =====

    /**
     * Regression: the client mints its anonymousId while it is built, and install
     * detection used to read storage afterwards — every fresh install reported
     * `Application Updated`.
     */
    @Test
    fun `fresh install reports Application Installed`() = runBlocking<Unit> {
        val events = coldLaunch()
        assertEquals("Application Installed", events.first().event)
    }

    @Test
    fun `host id set before the first initialize still reports Installed and is on the events`() = runBlocking<Unit> {
        assertTrue(manager.setAnonymousId("web-anon-1"))

        val events = coldLaunch {
            assertFalse("the marker's window ends at client construction", storage.hasHostSetMarker())
            assertTrue(manager.hasAnyValue())
        }

        assertEquals("Application Installed", events.first().event)
        assertTrue(events.all { it.anonymousId == "web-anon-1" })
    }

    @Test
    fun `host id over an existing id from a pre-lifecycle build reports Updated`() = runBlocking<Unit> {
        storage.set(KEY_ANONYMOUS_ID, "sdk-anon")
        assertTrue(manager.setAnonymousId("web-anon-1"))

        assertEquals("Application Updated", coldLaunch().first().event)
    }

    // ===== Pre-attach replay (no Context yet) =====

    @Test
    fun `set before storage attaches is applied at attach, marker included`() {
        val host = HostIdentity()
        assertTrue(host.setAnonymousId("web-anon-1"))
        assertNull(storage.get(KEY_ANONYMOUS_ID))

        host.attachStorage(storage)

        assertEquals("web-anon-1", storage.get(KEY_ANONYMOUS_ID))
        assertFalse(storage.hasAnyValue())
    }

    @Test
    fun `rejections before attach return false and record nothing`() {
        val host = HostIdentity()
        assertFalse(host.setAnonymousId("  "))

        host.attachStorage(storage)

        assertNull(storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `reset then set before attach keeps the id through the later clear`() {
        val host = HostIdentity()
        host.noteResetRequested()
        assertTrue(host.setAnonymousId("web-anon-2"))
        host.attachStorage(storage)

        storage.clear()

        assertEquals("web-anon-2", storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `set then reset before attach discards the id at the later clear`() {
        val host = HostIdentity()
        assertTrue(host.setAnonymousId("web-anon-1"))
        host.noteResetRequested()
        host.attachStorage(storage)

        storage.clear()

        assertNull(storage.get(KEY_ANONYMOUS_ID))
    }

    // ===== reset() ordering =====
    // The host's reset() notes the request synchronously; the clear runs later. These
    // drive the two halves separately, with the clear delayed past the set.

    @Test
    fun `host id set after the reset request survives the delayed clear`() = runBlocking<Unit> {
        manager.getAnonymousId()
        manager.setUserId("user-1")

        manager.noteResetRequested()
        assertTrue(manager.setAnonymousId("web-anon-2"))
        manager.reset()

        assertEquals("web-anon-2", manager.getAnonymousId())
        assertNull("the rest of identity is still cleared", storage.get(KEY_USER_ID))
    }

    @Test
    fun `host id set before the reset request is cleared`() = runBlocking<Unit> {
        assertTrue(manager.setAnonymousId("web-anon-1"))

        manager.noteResetRequested()
        manager.reset()

        assertNotEquals("web-anon-1", manager.getAnonymousId())
    }

    /** The unchanged-value shortcut cannot skip the bookkeeping. */
    @Test
    fun `re-setting the current id after the reset request keeps it`() = runBlocking<Unit> {
        assertTrue(manager.setAnonymousId("web-anon-1"))

        manager.noteResetRequested()
        assertTrue(manager.setAnonymousId("web-anon-1"))
        manager.reset()

        assertEquals("web-anon-1", manager.getAnonymousId())
    }

    @Test
    fun `two resets then a set keep the id through both clears`() = runBlocking<Unit> {
        manager.noteResetRequested()
        manager.noteResetRequested()
        assertTrue(manager.setAnonymousId("web-anon-1"))
        manager.reset()
        manager.reset()

        assertEquals("web-anon-1", manager.getAnonymousId())
    }

    @Test
    fun `set reset set reset clears the last id`() = runBlocking<Unit> {
        assertTrue(manager.setAnonymousId("web-anon-1"))
        manager.noteResetRequested()
        assertTrue(manager.setAnonymousId("web-anon-2"))
        manager.noteResetRequested()
        manager.reset()
        manager.reset()

        val current = manager.getAnonymousId()
        assertNotEquals("web-anon-1", current)
        assertNotEquals("web-anon-2", current)
    }

    /** A clear that no reset() requested is an ordinary clear, however recent the set. */
    @Test
    fun `unrequested clear still clears a host id`() = runBlocking<Unit> {
        assertTrue(manager.setAnonymousId("web-anon-1"))

        manager.reset()

        assertNull(storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `reset mints a new uuid on the next read`() = runBlocking<Unit> {
        assertTrue(manager.setAnonymousId("web-anon-1"))
        manager.noteResetRequested()
        manager.reset()

        UUID.fromString(manager.getAnonymousId())
    }

    /**
     * Pre-bind through the proxy: the reset is queued and replays at bind, after the set
     * wrote storage — the replayed clear must keep the id.
     */
    @Test
    fun `proxy reset then set before bind keeps the host id`() = runBlocking<Unit> {
        val consent = ConsentManager()
        val providers = ProviderContextManager()
        val proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, consent, providers, HostIdentity(storage))

        proxy.reset()
        assertTrue(proxy.setAnonymousId("web-anon-2"))
        val client = newClient(consent, providers, RecordingEventQueue(), trackLifecycle = false)
        proxy.bind(client)

        // The replayed reset ran on bind (the client is idle after it).
        assertEquals("web-anon-2", storage.get(KEY_ANONYMOUS_ID))
        assertEquals("web-anon-2", manager.getAnonymousId())
    }

    @Test
    fun `proxy set then reset while bound discards the host id`() = runBlocking<Unit> {
        val consent = ConsentManager()
        val providers = ProviderContextManager()
        val proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, consent, providers, HostIdentity(storage))
        proxy.bind(newClient(consent, providers, RecordingEventQueue(), trackLifecycle = false))

        assertTrue(proxy.setAnonymousId("web-anon-1"))
        proxy.reset()

        assertNull(storage.get(KEY_ANONYMOUS_ID))
    }

    /**
     * A proxy built without MetaRouter has no storage before bind; its pre-bind writes
     * land on the client's storage at bind, ahead of the queued reset they order against.
     */
    @Test
    fun `standalone proxy applies pre-bind writes at bind`() = runBlocking<Unit> {
        val consent = ConsentManager()
        val providers = ProviderContextManager()
        val proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, consent, providers)

        proxy.reset()
        assertTrue(proxy.setAnonymousId("web-anon-2"))
        proxy.bind(newClient(consent, providers, RecordingEventQueue(), trackLifecycle = false))

        assertEquals("web-anon-2", storage.get(KEY_ANONYMOUS_ID))
    }

    // ===== Helpers =====

    private suspend fun newClient(
        consent: ConsentManager,
        providers: ProviderContextManager,
        queue: EventQueueInterface,
        trackLifecycle: Boolean
    ): MetaRouterAnalyticsClient = MetaRouterAnalyticsClient.initialize(
        context,
        InitOptions(
            writeKey = "wk",
            ingestionHost = "https://events.example.com",
            flushIntervalSeconds = 999,
            trackLifecycleEvents = trackLifecycle
        ),
        identityManager = IdentityManager(storage),
        eventQueue = queue,
        lifecycleStorage = lifecycleStorage,
        consentManager = consent,
        providerContextManager = providers
    )

    /** Builds a lifecycle-tracking client and returns its events once the cold-launch one is in. */
    private suspend fun coldLaunch(whileRunning: () -> Unit = {}): List<EnrichedEventPayload> {
        val queue = RecordingEventQueue()
        val client = newClient(ConsentManager(), ProviderContextManager(), queue, trackLifecycle = true)
        try {
            val coldLaunch = setOf("Application Installed", "Application Updated")
            val start = System.currentTimeMillis()
            while (queue.events.none { it.event in coldLaunch }) {
                if (System.currentTimeMillis() - start > 2000) {
                    throw AssertionError("no cold-launch event enqueued: ${queue.events.map { it.event }}")
                }
                delay(20)
            }
            whileRunning()
            return queue.events.toList()
        } finally {
            client.reset()
        }
    }

    /** Records enqueued events and never lets the dispatcher drain them away. */
    private class RecordingEventQueue : EventQueueInterface {
        val events = CopyOnWriteArrayList<EnrichedEventPayload>()
        override fun size(): Int = events.size
        override fun enqueue(event: EnrichedEventPayload) { events.add(event) }
        override fun drain(max: Int): List<EnrichedEventPayload> = emptyList()
        override fun requeueToFront(events: List<EnrichedEventPayload>) {}
        override fun clear() { events.clear() }
    }
}
