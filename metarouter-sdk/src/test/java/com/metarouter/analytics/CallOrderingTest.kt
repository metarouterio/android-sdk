package com.metarouter.analytics

import android.content.Context
import android.net.Uri
import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.network.FakeNetworkClient
import com.metarouter.analytics.network.FakeNetworkMonitor
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.storage.IdentityStorage
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host calls reach the wire in call order, each carrying the identity set before it.
 */
@RunWith(RobolectricTestRunner::class)
class CallOrderingTest {

    private val network = FakeNetworkClient().apply { nextResponse = NetworkResponse(200, emptyMap(), null) }

    // A real context: identity lives in SharedPreferences, which a mock would not store.
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStorage() {
        IdentityStorage(context).clear()
        SessionStorage(context).clear()
    }

    private val clients = mutableListOf<MetaRouterAnalyticsClient>()

    // Stops each client's processor, so it can't run on into the next test.
    @After
    fun resetClients() = runBlocking { clients.forEach { it.reset() } }

    private suspend fun makeClient(fireSessionStarted: Boolean = false): MetaRouterAnalyticsClient {
        return MetaRouterAnalyticsClient.initialize(
            context,
            InitOptions(
                writeKey = "wk",
                ingestionHost = "https://events.example.com",
                flushIntervalSeconds = 999,
                trackLifecycleEvents = false,
                fireSessionStarted = fireSessionStarted
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

    @Test
    fun `Session Started carries the identity of the event that started the session`() = runBlocking {
        val client = makeClient(fireSessionStarted = true)

        client.identify("u1")
        client.track("first")
        client.identify("u2")

        val events = postedEvents(client, 4)
        val sessionStarted = events.single { (it["event"] as? JsonPrimitive)?.content == "Session Started" }
        assertEquals(JsonPrimitive("u1"), sessionStarted["userId"])
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

    @Test
    fun `a queued flush waiting on the network does not hold getAnonymousId`() = runBlocking {
        val proxy = AnalyticsProxy()
        val network = CompletableDeferred<Unit>()
        val client = object : AnalyticsInterface by BlockingClient(CountDownLatch(0)) {
            override suspend fun flush() = network.await()
        }
        proxy.track("queued")
        launch { proxy.flush() }
        yield()

        val bind = launch(Dispatchers.Default) { proxy.bind(client) }
        val id = withTimeoutOrNull(1000) { proxy.getAnonymousId() }
        network.complete(Unit)
        bind.join()

        assertEquals("anon", id)
    }

    @Test
    fun `bind finishes while a host thread keeps calling faster than replay`() = runBlocking {
        val proxy = AnalyticsProxy()
        // Each replayed call costs 10µs, more than a queued call does, so every pass
        // finds a bigger backlog than the last: bind must publish anyway.
        val client = object : AnalyticsInterface by BlockingClient(CountDownLatch(0)) {
            override fun track(event: String, properties: Map<String, Any?>?) {
                val until = System.nanoTime() + 10_000
                while (System.nanoTime() < until) Thread.onSpinWait()
            }
        }
        repeat(500) { proxy.track("queued-$it") }
        val stop = AtomicBoolean(false)
        val host = Thread { while (!stop.get()) proxy.track("flood") }.apply { start() }
        val stopper = Thread {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
            stop.set(true)
        }.apply { start() }

        val start = System.nanoTime()
        proxy.bind(client)
        val bindMs = (System.nanoTime() - start) / 1_000_000
        val stillFlooding = !stop.get()
        stop.set(true)
        host.join()
        stopper.interrupt()

        assertTrue("bind waited for the host to stop calling (${bindMs}ms)", stillFlooding)
        assertTrue("bind took ${bindMs}ms", bindMs < 1000)
    }

    /** `track(x); flush()` sends x: the flush waits for the events still in the channel. */
    @Test
    fun `a flush sends the events tracked before it`() = runBlocking {
        val client = makeClient()

        val missed = (0 until 50).filter { i ->
            client.track("t$i")
            client.flush()
            network.requests.none { it.body.decodeToString().contains("\"t$i\"") }
        }

        assertEquals("tracks the flush right after them did not send", emptyList<Int>(), missed)
    }

    /** Two threads identifying at once: each identify event carries its own userId. */
    @Test
    fun `concurrent identifies each carry their own userId`() = runBlocking {
        val client = makeClient()
        val rounds = 200

        repeat(rounds) { i ->
            val start = CountDownLatch(1)
            val threads = listOf("a$i", "b$i").map { id ->
                Thread { start.await(); client.identify(id) }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join() }
        }

        // A mixed-up pair sends one id twice and the other not at all.
        val sent = postedEvents(client, rounds * 2)
            .filter { it["type"] == JsonPrimitive("identify") }
            .map { (it["userId"] as JsonPrimitive).content }
        val duplicated = sent.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertEquals(rounds * 2, sent.size)
        assertEquals("identify events with another call's userId", emptyList<String>(), duplicated)
    }
}
