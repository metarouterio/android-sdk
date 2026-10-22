package com.metarouter.analytics.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.AnalyticsInterface
import com.metarouter.analytics.AnalyticsProxy
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.MetaRouter
import com.metarouter.analytics.consent.ConsentManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Provider context through the proxy acts on the shared manager directly — never
 * queued, never dependent on a bind, never forwarded to the bound client (which stamps
 * from that same manager). The queue bypass is what these pin: the pending-call queue
 * drops its OLDEST entry at capacity, so a queued set would be the first casualty of a
 * pre-initialize burst.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderContextProxyTest {

    private lateinit var appContext: Context
    private lateinit var storage: ProviderContextStorage
    private lateinit var manager: ProviderContextManager
    private lateinit var proxy: AnalyticsProxy
    private lateinit var mockClient: AnalyticsInterface

    private val expected = mapOf("googleFirebase" to buildJsonObject { put("appInstanceId", "abc123") })

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        storage = ProviderContextStorage(appContext).apply { clear() }
        manager = ProviderContextManager(storage)
        proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, ConsentManager(), manager)
        mockClient = mockk(relaxed = true)
        runBlocking { MetaRouter.resetForTesting() }
        // The process-wide manager outlives tests: another test's initialize may have
        // attached storage already.
        MetaRouter.providerContextManagerForTesting.replaceStorageForTesting(null)
    }

    @After
    fun tearDown() = runBlocking {
        MetaRouter.resetForTesting()
        MetaRouter.providerContextManagerForTesting.replaceStorageForTesting(null)
        storage.clear()
        unmockkAll()
    }

    private fun mockContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return context
    }

    // ===== Through MetaRouter: one manager for proxy and client =====

    /**
     * The public entry point, end to end: set on `MetaRouter.Analytics.shared` before
     * `initialize`, read through the bound client, then `reset()` and re-initialize.
     */
    @Test
    fun `set before initialize through MetaRouter survives reset and reinitialize`() = runBlocking {
        MetaRouter.providerContextManagerForTesting.replaceStorageForTesting(storage)
        val options = InitOptions(writeKey = "wk", ingestionHost = "https://events.example.com", trackLifecycleEvents = false)

        assertTrue(MetaRouter.Analytics.shared.setProviderContext("googleFirebase", "appInstanceId", "abc123"))
        assertFalse(MetaRouter.Analytics.shared.setProviderContext("Meta_Router", "sessionID", "x"))

        val client = MetaRouter.initializeAndWait(mockContext(), options)
        assertEquals(expected, client.getProviderContext())
        assertEquals(mapOf("googleFirebase" to listOf("appInstanceId")), client.getDebugInfo()["providerContext"])

        MetaRouter.Analytics.resetAndWait()
        assertEquals("reset() leaves provider context", expected, MetaRouter.Analytics.shared.getProviderContext())
        assertEquals(expected, storage.get())

        val reinitialized = MetaRouter.initializeAndWait(mockContext(), options)
        assertEquals(expected, reinitialized.getProviderContext())
    }

    /**
     * The Android-only step: storage attaches at initialize. A previous launch's values
     * load then, and a pre-initialize write lands on top of them.
     */
    @Test
    fun `initialize loads persisted values and keeps pre-initialize writes on top`() = runBlocking {
        storage.set(mapOf("googleFirebase" to buildJsonObject { put("appInstanceId", "old"); put("npa", true) }))
        val shared = MetaRouter.Analytics.shared
        shared.setProviderContext("googleFirebase", "appInstanceId", "abc123")
        assertEquals("not loaded before initialize", expected, shared.getProviderContext())

        MetaRouter.initializeAndWait(
            appContext,
            InitOptions(writeKey = "wk", ingestionHost = "https://events.example.com", trackLifecycleEvents = false)
        )

        val merged = mapOf("googleFirebase" to buildJsonObject { put("appInstanceId", "abc123"); put("npa", true) })
        assertEquals(merged, shared.getProviderContext())
        assertEquals(merged, storage.get())
    }

    // ===== Before any bind =====

    @Test
    fun `provider context is fully usable before bind`() {
        proxy.setProviderContext("googleFirebase", "appInstanceId", "abc123")

        assertEquals(expected, proxy.getProviderContext())
        assertEquals("persisted immediately — nothing waits for a bind", expected, storage.get())
    }

    @Test
    fun `clear works before bind`() {
        proxy.setProviderContext("googleFirebase", "appInstanceId", "abc123")
        proxy.clearProviderContext("googleFirebase")

        assertEquals(emptyMap<String, JsonObject>(), proxy.getProviderContext())
        assertNull(storage.get())
    }

    @Test
    fun `clearAll works before bind`() {
        proxy.setProviderContext("a", "k", "1")
        proxy.setProviderContext("b", "k", "2")
        proxy.clearAllProviderContext()

        assertEquals(emptyMap<String, JsonObject>(), proxy.getProviderContext())
    }

    // ===== Queue bypass =====

    /**
     * The regression the design exists to avoid: the pending-call queue evicts the
     * OLDEST entry at capacity, so a queued set would lose precisely the value every
     * subsequent event needs.
     */
    @Test
    fun `set survives a pre-bind burst that overflows the call queue`() = runTest {
        val small = AnalyticsProxy(maxPendingCalls = 5, consentManager = ConsentManager(), providerContextManager = manager)
        small.setProviderContext("googleFirebase", "appInstanceId", "abc123")
        repeat(60) { small.track("filler-$it") }

        assertEquals(expected, small.getProviderContext())

        small.bind(mockClient)

        assertEquals("still there after the queue replayed and dropped events", expected, small.getProviderContext())
    }

    @Test
    fun `set is never forwarded to the bound client`() = runTest {
        proxy.bind(mockClient)

        proxy.setProviderContext("googleFirebase", "appInstanceId", "abc123")
        proxy.getProviderContext()
        proxy.clearProviderContext("googleFirebase")
        proxy.clearAllProviderContext()

        // The client stamps from this same manager; forwarding would be a second,
        // queue-racing write of the same state.
        verify(exactly = 0) {
            mockClient.setProviderContext(any(), any(), any())
            mockClient.getProviderContext()
            mockClient.clearProviderContext(any())
            mockClient.clearAllProviderContext()
        }
    }

    @Test
    fun `values set before bind remain visible after unbind`() = runTest {
        proxy.setProviderContext("googleFirebase", "appInstanceId", "abc123")
        proxy.bind(mockClient)
        proxy.unbind()

        // reset() tears the client down; this is install-scoped and outlives it,
        // exactly like consent.
        assertEquals(expected, proxy.getProviderContext())
    }

    // ===== Debug info =====

    @Test
    fun `pre-bind debug info reports the shape the SDK is holding`() = runTest {
        proxy.setProviderContext("googleFirebase", "appInstanceId", "abc123")

        assertEquals(mapOf("googleFirebase" to listOf("appInstanceId")), proxy.getDebugInfo()["providerContext"])
    }
}
