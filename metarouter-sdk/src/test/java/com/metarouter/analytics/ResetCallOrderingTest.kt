package com.metarouter.analytics

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.storage.IdentityStorage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * A call made right after `reset()` belongs to the next client, as on iOS, and the
 * reset leaves nothing behind: no orphan client, no stranded waiter, no client stuck idle.
 */
@RunWith(RobolectricTestRunner::class)
class ResetCallOrderingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Non-debuggable view of the app, so an invalid config takes the release refusal path.
    private val releaseContext: Context = object : ContextWrapper(context) {
        override fun getApplicationInfo(): ApplicationInfo =
            ApplicationInfo(context.applicationInfo).apply { flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
    }

    private fun options(writeKey: String) = InitOptions(
        writeKey = writeKey,
        ingestionHost = "https://events.example.com",
        flushIntervalSeconds = 999,
        trackLifecycleEvents = false
    )

    // Held by a test to queue several lifecycle calls before the first one runs.
    private val initMutex: Mutex
        get() = MetaRouter::class.java.getDeclaredField("initMutex")
            .apply { isAccessible = true }.get(MetaRouter) as Mutex

    @Before
    fun clearStorage() {
        IdentityStorage(context).clear()
        SessionStorage(context).clear()
        // The process-wide instance outlives tests; point it at this test's app.
        MetaRouter.hostIdentityForTesting.replaceStorageForTesting(IdentityStorage(context))
        ShadowLog.clear()
    }

    @After
    fun teardown() = runBlocking { MetaRouter.resetForTesting() }

    private suspend fun awaitQueueLength(analytics: AnalyticsInterface, length: Int): Map<String, Any?> {
        var info = analytics.getDebugInfo()
        val start = System.currentTimeMillis()
        while ((info["queueLength"] as? Int ?: 0) < length && System.currentTimeMillis() - start < 2000) {
            delay(20)
            info = analytics.getDebugInfo()
        }
        return info
    }

    @Test
    fun `an identify and track made right after reset reach the next client`() = runBlocking {
        val lost = mutableListOf<Int>()
        for (round in 0 until 20) {
            MetaRouter.Analytics.initializeAndWait(context, options("wk-a111"))

            MetaRouter.Analytics.reset()
            MetaRouter.Analytics.shared.identify("u$round")
            MetaRouter.Analytics.shared.track("after reset")
            val analytics = MetaRouter.Analytics.initialize(context, options("wk-b222"))

            val info = awaitQueueLength(analytics, 2)
            if (info["queueLength"] != 2 || info["userId"] == null) lost += round
            MetaRouter.Analytics.resetAndWait()
        }
        assertEquals("rounds whose calls missed the next client", emptyList<Int>(), lost)
    }

    /** reset() lands while the first initialize is still building its client. */
    @Test
    fun `an identify after a reset during initialize reaches the next client`() = runBlocking {
        val lost = mutableListOf<Int>()
        for (round in 0 until 40) {
            MetaRouter.Analytics.initialize(context, options("wk-a111"))
            if (round % 4 != 0) Thread.sleep(0, (round * 25_000) % 1_000_000)
            if (round % 8 == 7) Thread.sleep((round / 8).toLong())

            MetaRouter.Analytics.reset()
            MetaRouter.Analytics.shared.identify("u$round")
            MetaRouter.Analytics.shared.track("after reset")
            val analytics = MetaRouter.Analytics.initialize(context, options("wk-b222"))

            val info = awaitQueueLength(analytics, 2)
            if (info["queueLength"] != 2 || info["userId"] == null) lost += round
            MetaRouter.Analytics.resetAndWait()
        }
        assertEquals("rounds whose calls missed the next client", emptyList<Int>(), lost)
    }

    // Control: the same calls made once the next client is initialized are counted.
    @Test
    fun `calls made after the next initialize reach it`() = runBlocking {
        MetaRouter.Analytics.initializeAndWait(context, options("wk-a111"))
        MetaRouter.Analytics.resetAndWait()
        val analytics = MetaRouter.Analytics.initializeAndWait(context, options("wk-b222"))
        analytics.identify("u")
        analytics.track("after initialize")

        assertEquals(2, awaitQueueLength(analytics, 2)["queueLength"])
    }

    @Test
    fun `two initializes queued before a reset leave no orphan client or observer`() = runBlocking {
        val baseline = observerCount()
        val mutex = initMutex
        mutex.lock()
        MetaRouter.Analytics.initialize(context, options("wk-a111"))
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            MetaRouter.Analytics.initializeAndWait(context, options("wk-a111"))
        }
        MetaRouter.Analytics.reset()
        mutex.unlock()
        second.join()
        MetaRouter.Analytics.initializeAndWait(context, options("wk-a111"))
        MetaRouter.Analytics.resetAndWait()

        val orphans = ShadowLog.getLogsForTag("MetaRouter").filter { it.msg.contains("Another client was stored") }
        assertEquals("clients built but never stored, so never reset", 0, orphans.size)
        assertEquals("lifecycle observers leaked", baseline, observerCount())
    }

    @Test
    fun `getAnonymousId after a reset on a refused session resolves at the next initialize`() = runBlocking {
        MetaRouter.initializeAndWait(releaseContext, options(""))
        val mutex = initMutex
        mutex.lock()
        MetaRouter.Analytics.initialize(context, options("wk-a111"))
        MetaRouter.Analytics.reset()
        val waiter = async(Dispatchers.Default) { MetaRouter.Analytics.shared.getAnonymousId() }
        delay(100)
        mutex.unlock()
        MetaRouter.Analytics.initializeAndWait(context, options("wk-a111"))

        val id = withTimeoutOrNull(3000) { waiter.await() }
        waiter.cancel()
        assertNotNull("getAnonymousId hung", id)
        assertTrue(id!!.isNotEmpty())
    }

    @Test
    fun `a waiter parked after detach on a refused session resolves at the next bind`() = runBlocking {
        val proxy = AnalyticsProxy()
        proxy.markConfigDisabled("bad")
        val epoch = proxy.epoch()
        proxy.detachCalls()
        val waiter = async(Dispatchers.Default) { proxy.getAnonymousId() }
        delay(100)
        proxy.clearConfigDisabled()
        assertFalse(proxy.bindIfCurrent(FakeClient(), epoch))
        proxy.release()
        proxy.bind(FakeClient())

        val id = withTimeoutOrNull(2000) { waiter.await() }
        waiter.cancel()
        assertEquals("anon", id)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `detachCalls racing release never strands a waiter`() = runBlocking {
        val pool = Executors.newSingleThreadExecutor()
        var stranded = 0
        try {
            for (i in 0 until 20_000) {
                val proxy = AnalyticsProxy()
                val barrier = CyclicBarrier(2)
                val releaser = pool.submit { barrier.await(); runBlocking { proxy.release() } }
                barrier.await()
                proxy.detachCalls()
                val waiter = GlobalScope.async(Dispatchers.Unconfined) { proxy.getAnonymousId() }
                releaser.get()
                proxy.bind(FakeClient())
                if (!waiter.isCompleted && withTimeoutOrNull(300) { waiter.await() } == null) {
                    waiter.cancel()
                    if (++stranded >= 3) break
                }
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("stranded waiters", 0, stranded)
    }

    @Test
    fun `an instance reset after the static one leaves the next client usable`() = runBlocking {
        val analytics = MetaRouter.Analytics.initializeAndWait(context, options("wk-a111"))
        MetaRouter.Analytics.reset()
        analytics.reset()
        assertUsable(MetaRouter.Analytics.initialize(context, options("wk-b222")))
    }

    @Test
    fun `an instance reset before any initialize leaves the next client usable`() = runBlocking {
        MetaRouter.Analytics.shared.reset()
        assertUsable(MetaRouter.Analytics.initialize(context, options("wk-b222")))
    }

    /**
     * The flush a bind replays runs outside the bind: the bind holds the lifecycle
     * lock getAnonymousId waits for, so awaiting the request there would hold it too.
     */
    @Test
    fun `a bind does not wait for the flush it replays`() = runBlocking {
        val proxy = AnalyticsProxy()
        val requestDone = CompletableDeferred<Unit>()
        val flushStarted = CompletableDeferred<Unit>()
        val client = object : AnalyticsInterface by FakeClient() {
            override suspend fun flush() {
                flushStarted.complete(Unit)
                requestDone.await()
            }
        }
        proxy.track("before init")
        proxy.flush()

        val bound = withTimeoutOrNull(1_000) { proxy.bind(client) }
        requestDone.complete(Unit)

        assertNotNull("bind waited for the replayed flush", bound)
        assertNotNull(withTimeoutOrNull(1_000) { flushStarted.await() })
    }

    /** Settings made before the reset are not the user's data and stay. */
    @Test
    fun `an instance reset before bind keeps settings`() = runBlocking {
        val proxy = AnalyticsProxy()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val client = object : AnalyticsInterface by FakeClient() {
            override fun track(event: String, properties: Map<String, Any?>?) { calls += "track:$event" }
            override fun setTracing(enabled: Boolean) { calls += "setTracing:$enabled" }
        }
        proxy.track("before reset")
        proxy.setTracing(true)
        proxy.reset()
        proxy.bind(client)

        assertEquals(listOf("setTracing:true"), calls.toList())
    }

    /**
     * A host reset() made while the bind is still replaying runs after the calls
     * queued before it, on the client: cleared on the side instead, the replay
     * would write the earlier identify back afterwards.
     */
    @Test
    fun `a reset during the bind replay runs after the queued calls`() = runBlocking {
        val proxy = AnalyticsProxy()
        val inReplay = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val client = object : AnalyticsInterface by FakeClient() {
            override fun identify(userId: String, traits: Map<String, Any?>?) {
                calls += "identify:$userId"
                inReplay.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            override suspend fun reset() { calls += "reset" }
        }
        proxy.identify("u1")

        val host = Thread {
            inReplay.await(5, TimeUnit.SECONDS)
            runBlocking { proxy.reset() }
            release.countDown()
        }.apply { start() }
        proxy.bind(client)
        host.join(5000)

        assertEquals(listOf("identify:u1", "reset"), calls)
    }

    private suspend fun assertUsable(analytics: AnalyticsInterface) {
        var info = analytics.getDebugInfo()
        val start = System.currentTimeMillis()
        while (info["bound"] != true && System.currentTimeMillis() - start < 3000) {
            delay(20)
            info = analytics.getDebugInfo()
        }
        assertEquals("lifecycle", "ready", info["lifecycle"])
        analytics.track("after")
        assertEquals("queueLength", 1, awaitQueueLength(analytics, 1)["queueLength"])
        withTimeout(2000) { analytics.getAnonymousId() }
    }

    private fun observerCount(): Int {
        shadowOf(Looper.getMainLooper()).idle()
        return (ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry).observerCount
    }

    private class FakeClient : AnalyticsInterface {
        override fun track(event: String, properties: Map<String, Any?>?) {}
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
}
