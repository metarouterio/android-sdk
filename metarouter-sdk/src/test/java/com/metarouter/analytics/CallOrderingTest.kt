package com.metarouter.analytics

import android.content.Context
import android.net.Uri
import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.network.FakeNetworkClient
import com.metarouter.analytics.network.FakeNetworkMonitor
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.storage.IdentityStorage
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Host calls reach the wire in call order, each carrying the identity set before it.
 */
@RunWith(RobolectricTestRunner::class)
class CallOrderingTest {

    private val network = FakeNetworkClient().apply { nextResponse = NetworkResponse(200, emptyMap(), null) }

    // A real context: identity lives in SharedPreferences, which a mock would not store.
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearIdentity() = IdentityStorage(context).clear()

    private val clients = mutableListOf<MetaRouterAnalyticsClient>()

    // Stops each client's processor, so it can't run on into the next test.
    @After
    fun resetClients() = runBlocking { clients.forEach { it.reset() } }

    private suspend fun makeClient(): MetaRouterAnalyticsClient {
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

    private suspend fun postedEvents(client: MetaRouterAnalyticsClient, count: Int): List<JsonObject> {
        val start = System.currentTimeMillis()
        while (true) {
            val events = network.requests.flatMap { request ->
                Json.parseToJsonElement(request.body.decodeToString())
                    .jsonObject.getValue("batch").jsonArray.map { it.jsonObject }
            }
            if (events.size >= count) return events
            if (System.currentTimeMillis() - start > 5000) throw AssertionError("${events.size} of $count POSTed")
            client.flush()
            delay(20)
        }
    }

    @Test
    fun `identify then track reach the wire in call order with that userId`() = runBlocking {
        val client = makeClient()

        repeat(50) { i ->
            client.identify("u$i")
            client.track("t$i")
        }

        val events = postedEvents(client, 100)
        val expected = (0 until 50).flatMap { listOf("identify:u$it", "track:u$it") }
        val actual = events.map { "${(it.getValue("type") as JsonPrimitive).content}:${(it["userId"] as JsonPrimitive).content}" }
        assertEquals(expected, actual)
    }

    @Test
    fun `a track carries the identity of its call, not of a later group`() = runBlocking {
        val client = makeClient()

        repeat(50) { i ->
            client.track("t$i")
            client.group("g$i")
        }

        val tracks = postedEvents(client, 100).filter { (it.getValue("type") as JsonPrimitive).content == "track" }
        assertEquals(
            (0 until 50).map { if (it == 0) null else "g${it - 1}" },
            tracks.map { (it["groupId"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        )
    }

    /** Records calls; the first replayed track blocks until the host has made a call. */
    private class BlockingClient(private val release: CountDownLatch) : AnalyticsInterface {
        val tracked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun track(event: String, properties: Map<String, Any?>?) {
            tracked.add(event)
            if (event == "queued-0") release.await(5, TimeUnit.SECONDS)
        }

        override fun identify(userId: String, traits: Map<String, Any?>?) {}
        override fun group(groupId: String, traits: Map<String, Any?>?) {}
        override fun screen(name: String, properties: Map<String, Any?>?) {}
        override fun page(name: String, properties: Map<String, Any?>?) {}
        override fun alias(newUserId: String) {}
        override fun setAdvertisingId(advertisingId: String) {}
        override fun clearAdvertisingId() {}
        override suspend fun flush() {}
        override suspend fun reset() {}
        override suspend fun getAnonymousId(): String = "anon"
        override suspend fun getSessionId(): String? = null
        override fun enableDebugLogging() {}
        override suspend fun getDebugInfo(): Map<String, Any?> = emptyMap()
        override fun setTracing(enabled: Boolean) {}
        override fun attachWebView(webView: android.webkit.WebView, allowedOrigins: List<String>) {}
        override fun recordOpenedUrl(uri: Uri, sourceApplication: String?) {}
    }

    @Test
    fun `a call made during the bind replay runs after the queued calls`() = runTest {
        val proxy = AnalyticsProxy()
        val release = CountDownLatch(1)
        val client = BlockingClient(release)
        repeat(3) { proxy.track("queued-$it") }

        // Called while the replay is blocked inside queued-0.
        val host = Thread {
            while (client.tracked.isEmpty()) Thread.sleep(1)
            proxy.track("during-bind")
            release.countDown()
        }.apply { start() }
        proxy.bind(client)
        host.join(5000)

        assertEquals(listOf("queued-0", "queued-1", "queued-2", "during-bind"), client.tracked)
    }
}
