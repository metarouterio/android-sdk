package com.metarouter.analytics

import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A queued call whose replay throws must not abort bind(): the remaining queue would
 * be stranded, the proxy would never bind, and getAnonymousId() would hang forever on
 * boundSignal.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyReplayHardeningTest {

    private class ThrowingClient : AnalyticsInterface {
        val tracked = mutableListOf<String>()

        override fun track(event: String, properties: Map<String, Any?>?) {
            if (event == "poison") throw IllegalStateException("boom")
            tracked.add(event)
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
        override fun enableDebugLogging() {}
        override suspend fun getDebugInfo(): Map<String, Any?> = emptyMap()
        override fun setTracing(enabled: Boolean) {}
        override fun openURL(uri: Uri, sourceApplication: String?) {}
    }

    @Test
    fun `a throwing queued call does not abort bind or strand later calls`() = runTest {
        val proxy = AnalyticsProxy()
        val client = ThrowingClient()

        proxy.track("before")
        proxy.track("poison")
        proxy.track("after")
        proxy.bind(client)

        assertTrue("bind must complete despite the poison call", proxy.isBound())
        assertEquals(listOf("before", "after"), client.tracked)
    }

    @Test
    fun `getAnonymousId resolves after a poisoned replay`() = runTest {
        val proxy = AnalyticsProxy()
        proxy.track("poison")
        proxy.bind(ThrowingClient())

        assertEquals("anon", proxy.getAnonymousId())
    }

    @Test
    fun `queue is empty and calls go direct after a poisoned bind`() = runTest {
        val proxy = AnalyticsProxy()
        val client = ThrowingClient()
        proxy.track("poison")
        proxy.bind(client)

        proxy.track("direct")

        assertEquals(0, proxy.pendingCallCount())
        assertEquals(listOf("direct"), client.tracked)
    }
}
