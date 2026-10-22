package com.metarouter.analytics.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.MetaRouterAnalyticsClient
import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.queue.EventQueueInterface
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.storage.LifecycleStorage
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.types.EventContext
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LibraryContext
import com.metarouter.analytics.types.PageContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Payload-attach for host-set provider context: every enriched event — whichever type
 * or source produced it, native or bridge-sourced — carries
 * `context.providers.<provider>.<key>` alongside the SDK's own `metarouter` session
 * block, read at enrichment time. The bridge-driven POST-body check lives in
 * `consent/ConsentClientPayloadTest`, next to the FakeNetworkClient harness.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderContextStampingTest {

    private lateinit var context: Context
    private lateinit var storage: ProviderContextStorage
    private lateinit var providerContext: ProviderContextManager
    private lateinit var service: EventEnrichmentService

    private val firebase = buildJsonObject { put("appInstanceId", "abc123") }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = ProviderContextStorage(context).apply { clear() }
        providerContext = ProviderContextManager(storage)
        service = makeService(providerContext)
    }

    @After
    fun tearDown() {
        storage.clear()
    }

    private fun makeService(manager: ProviderContextManager): EventEnrichmentService {
        val identityManager = mockk<IdentityManager> {
            coEvery { getAnonymousId() } returns "anon-1"
            every { getUserId() } returns null
            every { getGroupId() } returns null
            coEvery { getAdvertisingId() } returns null
        }
        val contextProvider = mockk<DeviceContextProvider> {
            every { getContext() } returns EventContext(library = LibraryContext("metarouter-android-sdk", "test"))
        }
        val sessionStorage = mockk<SessionStorage>(relaxUnitFun = true) {
            every { getSessionId() } returns null
            every { getSessionCount() } returns null
            every { getLastActivityMs() } returns null
        }
        return EventEnrichmentService(
            identityManager = identityManager,
            contextProvider = contextProvider,
            writeKey = "test-key",
            sessionManager = SessionManager(sessionStorage, wallClockMillis = { 1_757_400_000_000L }, monotonicClockMillis = { 50_000L }),
            consentManager = ConsentManager(mockk(relaxed = true), dispatch = { }),
            providerContextManager = manager
        )
    }

    private fun enrich(
        type: EventType = EventType.TRACK,
        event: String? = "Order Completed",
        on: EventEnrichmentService = service
    ): EnrichedEventPayload = runBlocking { on.enrichEvent(BaseEvent(type = type, event = event)) }

    // ===== Stamp on every event type =====

    @Test
    fun `every event type stamps provider context`() {
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")

        for (type in EventType.values()) {
            assertEquals("$type must carry the provider block", firebase, enrich(type).context.providers?.get("googleFirebase"))
        }
    }

    /**
     * The point of the feature: a value only the native app knows reaches events the
     * web page produced.
     */
    @Test
    fun `bridge-shaped event carries provider context and page`() {
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")

        val payload = runBlocking {
            service.enrichEvent(
                BaseEvent(
                    type = EventType.TRACK,
                    event = "Web Click",
                    page = PageContext(url = "https://shop.example.com/cart", title = "Cart")
                )
            )
        }

        assertEquals(firebase, payload.context.providers?.get("googleFirebase"))
        assertEquals("provider stamping must not disturb the page block", "https://shop.example.com/cart", payload.context.page?.url)
        assertNotNull("provider stamping must not disturb the consent block", payload.context.consent)
    }

    @Test
    fun `multiple providers all appear`() {
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")
        providerContext.setValue("googleFirebase", "nonPersonalizedAds", false)
        providerContext.setValue("adjust", "adid", "adj-1")

        val providers = enrich().context.providers

        assertEquals(
            buildJsonObject {
                put("appInstanceId", "abc123")
                put("nonPersonalizedAds", false)
            },
            providers?.get("googleFirebase")
        )
        assertEquals(buildJsonObject { put("adid", "adj-1") }, providers?.get("adjust"))
    }

    // ===== Coexistence with the SDK's own block =====

    @Test
    fun `session block survives alongside host providers`() {
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")

        val providers = enrich().context.providers

        assertNotNull(providers?.get("metarouter")?.get("sessionID"))
        assertNotNull(providers?.get("metarouter")?.get("sessionCount"))
        assertEquals(2, providers?.size)
    }

    /** Pins the no-op case: an untouched API emits the same providers block as before this feature. */
    @Test
    fun `no host providers leaves the session block exactly as before`() {
        val providers = enrich().context.providers

        assertEquals(1, providers?.size)
        assertNotNull(providers?.get("metarouter")?.get("sessionID"))
    }

    /**
     * The API refuses `metarouter`, so this covers the other half: a value that reached
     * the map by another route (a storage blob from a build with different rules) still
     * cannot displace the session stamp.
     */
    @Test
    fun `session block wins over a metarouter entry that bypassed the API`() {
        storage.set(
            mapOf(
                "metarouter" to buildJsonObject {
                    put("sessionID", "hijacked")
                    put("sessionCount", 999)
                }
            )
        )
        val smuggled = ProviderContextManager(storage)
        assertEquals("precondition: the value is in the map", JsonPrimitive("hijacked"), smuggled.getAll()["metarouter"]?.get("sessionID"))

        val session = enrich(on = makeService(smuggled)).context.providers?.get("metarouter")

        assertNotEquals(JsonPrimitive("hijacked"), session?.get("sessionID"))
        assertEquals(JsonPrimitive(1), session?.get("sessionCount"))
    }

    // ===== Point-in-time =====

    @Test
    fun `stamp reflects state at enrichment time`() {
        val before = enrich(event = "Before")
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")
        val after = enrich(event = "After")

        assertNull(
            "an already-enriched payload is immutable; a later set never rewrites it",
            before.context.providers?.get("googleFirebase")
        )
        assertEquals(firebase, after.context.providers?.get("googleFirebase"))
    }

    @Test
    fun `removal stops the stamp`() {
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")
        enrich(event = "With")
        providerContext.clear("googleFirebase")

        val providers = enrich(event = "Without").context.providers

        assertNull(providers?.get("googleFirebase"))
        assertEquals("only the session block remains", 1, providers?.size)
    }

    @Test
    fun `value persisted by a prior launch is stamped without any set this process`() {
        storage.set(mapOf("googleFirebase" to buildJsonObject { put("appInstanceId", "from-last-launch") }))

        // Stands in for the next cold launch — what gets Application Opened and the
        // first Session Started stamped, which a memory-only store would miss.
        val payload = enrich(event = "Application Opened", on = makeService(ProviderContextManager(storage)))

        assertEquals(buildJsonObject { put("appInstanceId", "from-last-launch") }, payload.context.providers?.get("googleFirebase"))
    }

    // ===== Wire shape =====

    @Test
    fun `provider block encodes at the expected JSON path`() {
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")

        val json = Json { encodeDefaults = true }.encodeToString(EnrichedEventPayload.serializer(), enrich())
        val root = Json.parseToJsonElement(json).jsonObject

        // Pins the exact path a pipeline mapping matches verbatim. Renaming any segment
        // forks every mapping keyed on it.
        assertEquals(
            JsonPrimitive("abc123"),
            root.getValue("context").jsonObject.getValue("providers").jsonObject
                .getValue("googleFirebase").jsonObject["appInstanceId"]
        )
    }

    // ===== Cold launch =====

    /** Records enqueued events and never lets the dispatcher drain them away. */
    private class RecordingEventQueue : EventQueueInterface {
        val events = CopyOnWriteArrayList<EnrichedEventPayload>()
        override fun size(): Int = events.size
        override fun enqueue(event: EnrichedEventPayload) { events.add(event) }
        override fun drain(max: Int): List<EnrichedEventPayload> = emptyList()
        override fun requeueToFront(events: List<EnrichedEventPayload>) {}
        override fun clear() { events.clear() }
    }

    /**
     * The cold-launch events go through the same enrichment funnel, so a value set before
     * `initialize` is on them too. Installed or Updated depending on what earlier tests
     * left in the identity store — either is the cold-launch event under test.
     */
    @Test
    fun `cold-launch events carry provider context set beforehand`() = runBlocking {
        val identityManager = IdentityManager(context).apply { reset() }
        val lifecycleStorage = LifecycleStorage(context).apply { clear() }
        val queue = RecordingEventQueue()
        providerContext.setValue("googleFirebase", "appInstanceId", "abc123")

        val client = MetaRouterAnalyticsClient.initialize(
            context,
            InitOptions(writeKey = "wk", ingestionHost = "https://events.example.com", flushIntervalSeconds = 999, trackLifecycleEvents = true),
            identityManager = identityManager,
            eventQueue = queue,
            lifecycleStorage = lifecycleStorage,
            providerContextManager = providerContext
        )
        try {
            val start = System.currentTimeMillis()
            val coldLaunch = setOf("Application Installed", "Application Updated")
            while (queue.events.none { it.event in coldLaunch }) {
                if (System.currentTimeMillis() - start > 2000) {
                    throw AssertionError("no cold-launch event enqueued: ${queue.events.map { it.event }}")
                }
                delay(20)
            }

            val installed = queue.events.first { it.event in coldLaunch }
            assertEquals(firebase, installed.context.providers?.get("googleFirebase"))
            assertNotNull("session block alongside", installed.context.providers?.get("metarouter"))
        } finally {
            client.reset()
            lifecycleStorage.clear()
        }
    }
}
