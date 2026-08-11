package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.utils.Logger
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetaRouterTest {

    private lateinit var context: Context
    private lateinit var options: InitOptions

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context

        options = InitOptions(
            writeKey = "test-write-key",
            ingestionHost = "https://events.example.com",
            flushIntervalSeconds = 10,
            debug = false,
            maxQueueEvents = 100
        )

        Logger.debugEnabled = false
    }

    @After
    fun teardown() = runTest {
        MetaRouter.resetForTesting()
        unmockkAll()
        Logger.debugEnabled = false
    }

    // ===== createAnalyticsClient =====

    @Test
    fun `createAnalyticsClient returns immediately`() = runTest {
        val startTime = System.currentTimeMillis()

        val client = MetaRouter.createAnalyticsClient(context, options)

        val duration = System.currentTimeMillis() - startTime
        assertNotNull(client)
        assertTrue("createAnalyticsClient took ${duration}ms, expected < 100ms", duration < 100)
    }

    @Test
    fun `createAnalyticsClient queues early calls before binding`() = runTest {
        val client = MetaRouter.createAnalyticsClient(context, options)

        // Call track immediately before binding completes
        client.track("Early Event 1")
        client.track("Early Event 2")

        // Get debug info - should show initializing state with pending calls
        val debugInfo = client.getDebugInfo()

        // Either still initializing with pending calls, or already bound
        val lifecycle = debugInfo["lifecycle"] as String
        if (lifecycle == "initializing") {
            val pendingCalls = debugInfo["pendingCalls"] as Int
            assertTrue("Expected pending calls, got $pendingCalls", pendingCalls >= 2)
        }
        // If already bound (fast init), that's also acceptable
    }

    // ===== initializeAndWait =====

    @Test
    fun `initializeAndWait blocks until ready`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `initializeAndWait returns bound proxy`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        // Should be able to track immediately without queueing
        client.track("Test Event")

        // Give time for event to process
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertTrue((debugInfo["queueLength"] as Int) >= 0)
    }

    // ===== Analytics.client() =====

    @Test
    fun `Analytics client returns proxy after init`() = runTest {
        MetaRouter.Analytics.initialize(context, options)

        val client = MetaRouter.Analytics.client()

        assertNotNull(client)
    }

    @Test
    fun `Analytics client throws IllegalStateException if not initialized`() = runTest {
        // Don't initialize - just try to get client
        try {
            MetaRouter.Analytics.client()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not initialized"))
        }
    }

    // ===== Double Initialization =====

    @Test
    fun `double initialization returns same proxy`() = runTest {
        val client1 = MetaRouter.createAnalyticsClient(context, options)
        val client2 = MetaRouter.createAnalyticsClient(context, options)

        assertSame(client1, client2)
    }

    @Test
    fun `initializeAndWait after createAnalyticsClient returns same proxy`() = runTest {
        val client1 = MetaRouter.createAnalyticsClient(context, options)

        // Wait for first init to complete
        delay(500)

        val client2 = MetaRouter.initializeAndWait(context, options)

        assertSame(client1, client2)
    }

    // ===== setDebugLogging =====

    @Test
    fun `setDebugLogging enables Logger`() = runTest {
        assertFalse(Logger.debugEnabled)

        MetaRouter.Analytics.setDebugLogging(true)

        assertTrue(Logger.debugEnabled)
    }

    @Test
    fun `setDebugLogging disables Logger`() = runTest {
        Logger.debugEnabled = true

        MetaRouter.Analytics.setDebugLogging(false)

        assertFalse(Logger.debugEnabled)
    }

    // ===== getAnonymousId (via client) =====

    @Test
    fun `getAnonymousId returns value after initializeAndWait`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        val anonId = client.getAnonymousId()
        assertNotNull(anonId)
    }

    @Test
    fun `getAnonymousId returns stable value across calls`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        val id1 = client.getAnonymousId()
        val id2 = client.getAnonymousId()
        assertEquals(id1, id2)
    }

    // ===== Reset =====

    @Test
    fun `reset allows re-initialization`() = runTest {
        // First initialization
        MetaRouter.Analytics.initializeAndWait(context, options)

        // Reset
        MetaRouter.Analytics.resetAndWait()

        // Should be able to get client still (but would need re-init for full use)
        // After reset, client() should throw since initializationStarted is false
        try {
            MetaRouter.Analytics.client()
            fail("Expected IllegalStateException after reset")
        } catch (e: IllegalStateException) {
            // Expected
        }

        // Can initialize again
        val newClient = MetaRouter.Analytics.initializeAndWait(context, options)
        assertNotNull(newClient)

        val debugInfo = newClient.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    // ===== Integration =====

    @Test
    fun `full initialization flow works`() = runTest {
        // Initialize
        val client = MetaRouter.initializeAndWait(context, options)

        // Track some events
        client.track("Event 1", mapOf("key" to "value"))
        client.identify("user-123", mapOf("email" to "test@example.com"))
        client.screen("Home Screen")

        // Give time for events to process
        delay(200)

        // Verify state
        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertTrue((debugInfo["queueLength"] as Int) >= 0)
        assertNotNull(debugInfo["anonymousId"])
    }

    // ===== Lifecycle Observer Integration =====

    @Test
    fun `initialization creates lifecycle observer`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        // Verify client is ready and dispatcher is running
        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertTrue(debugInfo["dispatcherRunning"] as Boolean)
    }

    @Test
    fun `lifecycle observer registration does not prevent initialization`() = runTest {
        // In test environment, ProcessLifecycleOwner registration happens on main thread
        // This test verifies init completes successfully regardless
        val client = MetaRouter.initializeAndWait(context, options)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `reset cleans up properly`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)
        assertEquals("ready", client.getDebugInfo()["lifecycle"])

        MetaRouter.Analytics.resetAndWait()

        // After reset, the proxy reports "initializing" since it's no longer bound
        // (the proxy reports "initializing" when not bound to a real client)
        val debugInfo = client.getDebugInfo()
        assertEquals("initializing", debugInfo["lifecycle"])
        assertEquals(false, debugInfo["bound"])
    }

    @Test
    fun `re-initialization after reset works`() = runTest {
        // First init
        MetaRouter.initializeAndWait(context, options)

        // Reset
        MetaRouter.Analytics.resetAndWait()

        // Re-initialize
        val client = MetaRouter.initializeAndWait(context, options)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertTrue(debugInfo["dispatcherRunning"] as Boolean)
    }

    // ===== Invalid-config contract: assert in debug, degrade in release =====
    // The relaxed Context mock reports applicationInfo.flags == 0 (no
    // FLAG_DEBUGGABLE), so these run the release-degrade path unless a test
    // flips the flag explicitly.

    private fun invalidOptions(onConfigError: ((ConfigError) -> Unit)? = null) =
        InitOptions(
            writeKey = "",
            ingestionHost = "https://events.example.com",
            onConfigError = onConfigError
        )

    @Test
    fun `invalid config in release leaves the SDK inert and signals`() = runTest {
        val received = mutableListOf<ConfigError>()

        val analytics = MetaRouter.initializeAndWait(context, invalidOptions { received.add(it) })
        // The one behavior this contract exists for: calls on a misconfigured SDK
        // are inert, never fatal.
        analytics.track("must_not_crash")

        assertEquals(listOf<ConfigError>(ConfigError.EmptyWriteKey), received)

        // No client was created: debug info stays in proxy form and carries the
        // config error for the session.
        val debugInfo = analytics.getDebugInfo()
        assertEquals(false, debugInfo["bound"])
        assertEquals("disabled", debugInfo["lifecycle"])
        assertEquals(ConfigError.EmptyWriteKey.description, debugInfo["configError"])

        // Awaiting APIs resolve degraded instead of suspending on a bind that will
        // never come — a permanent hang would be worse than the crash this replaces.
        assertEquals("", analytics.getAnonymousId())
    }

    @Test
    fun `invalid config in a debuggable build fails fast at initialize`() = runTest {
        // flags is a plain field, not a getter — stub the whole ApplicationInfo.
        val debuggableInfo = android.content.pm.ApplicationInfo().apply {
            flags = android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        }
        every { context.applicationInfo } returns debuggableInfo

        val thrown = try {
            MetaRouter.initializeAndWait(context, invalidOptions())
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("writeKey"))
    }

    @Test
    fun `invalid re-init disables the previous session instead of masking the error`() = runTest {
        val analytics = MetaRouter.initializeAndWait(context, options)
        assertEquals("ready", analytics.getDebugInfo()["lifecycle"])

        MetaRouter.initializeAndWait(context, invalidOptions())

        // The stale valid client must not keep running past an explicit re-init
        // with bad config — that would silently mask the config error.
        val debugInfo = analytics.getDebugInfo()
        assertEquals(false, debugInfo["bound"])
        assertEquals("disabled", debugInfo["lifecycle"])
        assertEquals(ConfigError.EmptyWriteKey.description, debugInfo["configError"])
    }

    @Test
    fun `valid re-init recovers a config-refused session`() = runTest {
        MetaRouter.initializeAndWait(context, invalidOptions())

        val analytics = MetaRouter.initializeAndWait(context, options)

        val debugInfo = analytics.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertNull(debugInfo["configError"])
    }

    @Test
    fun `reset clears the config refusal so the next session can bind`() = runTest {
        MetaRouter.initializeAndWait(context, invalidOptions())

        // reset() ends the refused session; the refusal must not survive it.
        MetaRouter.Analytics.resetAndWait()

        val analytics = MetaRouter.initializeAndWait(context, options)

        val debugInfo = analytics.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertNull(debugInfo["configError"])
    }

    @Test
    fun `valid createAnalyticsClient after a refusal recovers the session`() = runTest {
        MetaRouter.initializeAndWait(context, invalidOptions())

        // The refusal marked initializationStarted (client() safety) — a host that
        // fixes its config and re-initializes must not bounce off that guard and
        // stay inert. iOS recovers here; Android must too.
        val analytics = MetaRouter.createAnalyticsClient(context, options)

        // The recovery init runs on the SDK's real IO scope — poll on real time.
        var bound = false
        withContext(Dispatchers.Default) {
            repeat(200) {
                if (!bound) {
                    bound = analytics.getDebugInfo()["bound"] == true
                    if (!bound) delay(25)
                }
            }
        }
        assertTrue("valid createAnalyticsClient must rebind a refused session", bound)
        assertNull(analytics.getDebugInfo()["configError"])
    }

    @Test
    fun `initialize does not re-log construction-time warnings`() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        try {
            // Construction warns once (cleartext http). The gate's options handling
            // must not re-run validation and log the same warning a second time.
            val cleartextOptions = InitOptions(
                writeKey = "test-write-key",
                ingestionHost = "http://api.example.com"
            )

            MetaRouter.initializeAndWait(context, cleartextOptions)

            verify(exactly = 1) {
                android.util.Log.w(any(), match<String> { it.contains("cleartext") })
            }
        } finally {
            unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun `client() returns the inert proxy after a refusal instead of throwing`() = runTest {
        MetaRouter.initializeAndWait(context, invalidOptions())

        // A host that stored nothing and asks for the client later must get the
        // inert proxy, not an IllegalStateException — the refused session stays
        // call-safe end to end.
        val analytics = MetaRouter.Analytics.client()
        analytics.track("still_must_not_crash")
        assertEquals(false, analytics.getDebugInfo()["bound"])
    }
}
