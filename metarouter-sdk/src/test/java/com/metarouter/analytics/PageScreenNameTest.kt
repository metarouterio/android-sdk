package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.consent.ConsentManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Page and screen names ride in `properties.name`, where iOS puts them and
 * integrations read them; `event` keeps the name too.
 */
@RunWith(RobolectricTestRunner::class)
class PageScreenNameTest {

    private val network = FakeNetworkClient().apply { nextResponse = NetworkResponse(200, emptyMap(), null) }

    private val clients = mutableListOf<MetaRouterAnalyticsClient>()

    // Stops each client's processor, so it can't run on into the next test.
    @After
    fun resetClients() = runBlocking { clients.forEach { it.reset() } }

    private suspend fun makeClient(): MetaRouterAnalyticsClient {
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
            consentManager = ConsentManager(),
            providerContextManager = ProviderContextManager()
        ).also { clients += it }
    }

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

    private fun JsonObject.properties() = getValue("properties").jsonObject

    @Test
    fun `screen puts the name in properties and keeps event`() = runBlocking {
        val client = makeClient()

        client.screen("Home", mapOf("tab" to "deals"))

        val event = firstPostedEvent(client)
        assertEquals(JsonPrimitive("Home"), event["event"])
        assertEquals(
            mapOf("tab" to JsonPrimitive("deals"), "name" to JsonPrimitive("Home")),
            event.properties()
        )
    }

    @Test
    fun `page without properties still carries the name`() = runBlocking {
        val client = makeClient()

        client.page("Checkout", null)

        val event = firstPostedEvent(client)
        assertEquals(JsonPrimitive("Checkout"), event["event"])
        assertEquals(mapOf("name" to JsonPrimitive("Checkout")), event.properties())
    }

    @Test
    fun `the name argument wins over a name property, as on iOS`() = runBlocking {
        val client = makeClient()

        client.screen("Home", mapOf("name" to "other"))

        assertEquals(JsonPrimitive("Home"), firstPostedEvent(client).properties()["name"])
    }

    @Test
    fun `bridge page copies the page facts into properties under the host's`() = runBlocking {
        val client = makeClient()
        val page = PageContext(
            url = "https://shop.example.com/p?q=1",
            path = "/p",
            search = "?q=1",
            title = "Product",
            referrer = "https://shop.example.com/"
        )

        val accepted = client.enqueueBridgeEvent(
            BridgeEnvelope(
                version = 1,
                messageId = "bridge-page",
                type = EventType.PAGE,
                name = "Product",
                properties = mapOf("title" to JsonPrimitive("Host title")),
                sentAt = null,
                page = page,
                source = null
            )
        )
        assertTrue(accepted)

        val event = firstPostedEvent(client)
        assertEquals(
            mapOf(
                "url" to JsonPrimitive("https://shop.example.com/p?q=1"),
                "path" to JsonPrimitive("/p"),
                "search" to JsonPrimitive("?q=1"),
                "title" to JsonPrimitive("Host title"),
                "referrer" to JsonPrimitive("https://shop.example.com/"),
                "name" to JsonPrimitive("Product")
            ),
            event.properties()
        )
        assertEquals("context.page is unchanged", "Product", event.getValue("context").jsonObject
            .getValue("page").jsonObject["title"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `bridge track properties are left as sent`() = runBlocking {
        val client = makeClient()

        client.enqueueBridgeEvent(
            BridgeEnvelope(
                version = 1,
                messageId = "bridge-track",
                type = EventType.TRACK,
                name = "Clicked",
                properties = mapOf("id" to JsonPrimitive(1)),
                sentAt = null,
                page = PageContext(url = "https://shop.example.com/"),
                source = null
            )
        )

        assertEquals(mapOf("id" to JsonPrimitive(1)), firstPostedEvent(client).properties())
    }
}
