package com.metarouter.analytics.consent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.MetaRouterAnalyticsClient
import com.metarouter.analytics.network.FakeNetworkClient
import com.metarouter.analytics.network.FakeNetworkMonitor
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.PageContext
import com.metarouter.analytics.webview.BridgeEnvelope
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Consent on the wire: a real client enriches, dispatches, and POSTs, and the
 * assertions read the request body. Covers both entry points — a native `track()`
 * and the WebView bridge sink — so neither depends on a hand-built payload matching
 * the path production takes.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentClientPayloadTest {

    private lateinit var storage: ConsentStorage
    private lateinit var network: FakeNetworkClient

    @Before
    fun setup() {
        val appContext: Context = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(appContext)
        storage.clear()
        network = FakeNetworkClient().apply { nextResponse = NetworkResponse(200, emptyMap(), null) }
    }

    @After
    fun tearDown() {
        storage.clear()
        unmockkAll()
    }

    private suspend fun makeClient(
        providerContextManager: ProviderContextManager = ProviderContextManager()
    ): MetaRouterAnalyticsClient {
        val consentManager = ConsentManager(storage, dispatch = { })
        consentManager.registerConsentCategories(
            listOf(ConsentCategory("c0001", defaultOptOut = false), ConsentCategory("c0004"))
        )
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("C0004" to false), timestamp = 1_757_400_000_000))

        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return MetaRouterAnalyticsClient.initialize(
            context,
            InitOptions(
                writeKey = "wk",
                ingestionHost = "https://events.example.com",
                flushIntervalSeconds = 999,
                trackLifecycleEvents = false
            ),
            eventQueue = EventQueue(),
            networkClient = network,
            networkMonitor = FakeNetworkMonitor(),
            consentManager = consentManager,
            providerContextManager = providerContextManager
        )
    }

    /**
     * Flushes until a POST lands and returns its first event. Repeated, because
     * enrichment is asynchronous: a flush before the event reaches the queue sends
     * nothing.
     */
    private suspend fun firstPostedEvent(client: MetaRouterAnalyticsClient): JsonObject {
        val start = System.currentTimeMillis()
        while (network.requests.isEmpty()) {
            if (System.currentTimeMillis() - start > 2000) throw AssertionError("nothing was POSTed")
            client.flush()
            delay(50)
        }
        val body = Json.parseToJsonElement(network.requests.first().body.decodeToString()).jsonObject
        return body.getValue("batch").jsonArray.first().jsonObject
    }

    private fun assertConsentBlock(event: JsonObject) {
        val consent = event.getValue("context").jsonObject["consent"]?.jsonObject
            ?: throw AssertionError("context.consent missing: $event")
        assertEquals(true, consent.getValue("explicit").jsonPrimitive.boolean)
        assertEquals(
            mapOf("c0001" to false, "c0004" to false),
            consent.getValue("optOut").jsonObject.mapValues { it.value.jsonPrimitive.boolean }
        )
        assertEquals("timestamp never reaches the wire", setOf("explicit", "optOut"), consent.keys)
        assertNull("not top-level", event["consent"])
    }

    @Test
    fun `track posts body with context consent`() = runBlocking {
        val client = makeClient()

        client.track("Order Completed")

        val event = firstPostedEvent(client)
        assertEquals(JsonPrimitive("Order Completed"), event["event"])
        assertConsentBlock(event)
    }

    /**
     * Enters through `enqueueBridgeEvent`, the sink the WebView message handler
     * calls, so the stamp is checked on the path the bridge actually uses.
     */
    @Test
    fun `bridge event posts body with context consent and page`() = runBlocking {
        val client = makeClient()

        val accepted = client.enqueueBridgeEvent(
            BridgeEnvelope(
                version = 1,
                messageId = "bridge-1",
                type = EventType.TRACK,
                name = "Web Click",
                properties = mapOf("sku" to JsonPrimitive("abc")),
                sentAt = null,
                page = PageContext(url = "https://shop.example.com/cart", title = "Cart"),
                source = null
            )
        )
        assertTrue(accepted)

        val event = firstPostedEvent(client)
        assertEquals(JsonPrimitive("Web Click"), event["event"])
        assertConsentBlock(event)
        assertEquals(
            JsonPrimitive("https://shop.example.com/cart"),
            event.getValue("context").jsonObject["page"]?.jsonObject?.get("url")
        )
    }

    /**
     * The bridge leg of provider context: a WebView page cannot know the app's vendor
     * ids, so they must land on bridge events from the native side.
     */
    @Test
    fun `bridge event posts body with provider context`() = runBlocking {
        val providers = ProviderContextManager().apply { setValue("googleFirebase", "appInstanceId", "abc123") }
        val client = makeClient(providers)

        assertTrue(
            client.enqueueBridgeEvent(
                BridgeEnvelope(
                    version = 1, messageId = "bridge-2", type = EventType.PAGE, name = "Cart", properties = emptyMap(),
                    sentAt = null, page = PageContext(url = "https://shop.example.com/cart", title = "Cart"), source = null
                )
            )
        )

        val block = firstPostedEvent(client).getValue("context").jsonObject.getValue("providers").jsonObject
        assertEquals(JsonPrimitive("abc123"), block["googleFirebase"]?.jsonObject?.get("appInstanceId"))
        assertTrue("session block alongside", block.containsKey("metarouter"))
    }
}
