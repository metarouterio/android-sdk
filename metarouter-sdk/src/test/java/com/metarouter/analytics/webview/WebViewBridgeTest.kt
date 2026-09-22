package com.metarouter.analytics.webview

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewBridgeTest {

    private class RecordingSink : BridgeEventSink {
        val enqueued = mutableListOf<BridgeEnvelope>()
        var accept = true
        override fun enqueue(envelope: BridgeEnvelope): Boolean {
            if (accept) enqueued.add(envelope)
            return accept
        }
    }

    private lateinit var webView: WebView
    private lateinit var sink: RecordingSink
    private lateinit var processor: BridgeMessageProcessor
    private val origins = listOf("https://www.metarouter.com")

    @Before
    fun setup() {
        mockkStatic(WebViewFeature::class)
        mockkStatic(WebViewCompat::class)
        webView = mockk(relaxed = true)
        sink = RecordingSink()
        processor = BridgeMessageProcessor(sink)
    }

    @After
    fun tearDown() {
        unmockkStatic(WebViewFeature::class)
        unmockkStatic(WebViewCompat::class)
    }

    private fun featuresSupported(supported: Boolean) {
        every { WebViewFeature.isFeatureSupported(any()) } returns supported
    }

    @Test
    fun `attach registers listener and wrapper script on supported devices`() {
        featuresSupported(true)
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } returns Unit
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()

        val attached = WebViewBridge.attach(webView, origins, processor)

        assertTrue(attached)
        verify(exactly = 1) {
            WebViewCompat.addWebMessageListener(
                webView,
                BridgeWrapperScript.NATIVE_CHANNEL_NAME,
                origins.toSet(),
                any()
            )
        }
        verify(exactly = 1) {
            WebViewCompat.addDocumentStartJavaScript(webView, any(), origins.toSet())
        }
    }

    @Test
    fun `attach is a no-op when the WebView provider lacks the features`() {
        featuresSupported(false)

        val attached = WebViewBridge.attach(webView, origins, processor)

        assertFalse(attached)
        verify(exactly = 0) { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }
        verify(exactly = 0) { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) }
    }

    @Test
    fun `attach rejects the wildcard origin`() {
        featuresSupported(true)

        val attached = WebViewBridge.attach(webView, listOf("*"), processor)

        assertFalse(attached)
        verify(exactly = 0) { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }
    }

    @Test
    fun `attach rejects an empty origin list`() {
        featuresSupported(true)

        assertFalse(WebViewBridge.attach(webView, emptyList(), processor))
    }

    @Test
    fun `attach rejects malformed origin rules instead of throwing downstream`() {
        featuresSupported(true)

        // Each of these would throw IllegalArgumentException inside the platform API
        // or silently never match the wrapper's exact-origin check.
        assertFalse(WebViewBridge.attach(webView, listOf("https://x.com/"), processor))
        assertFalse(WebViewBridge.attach(webView, listOf("https://x.com/path"), processor))
        assertFalse(WebViewBridge.attach(webView, listOf("x.com"), processor))
        assertFalse(WebViewBridge.attach(webView, listOf("https://*.x.com"), processor))
        verify(exactly = 0) { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }
    }

    @Test
    fun `attach accepts bracketed IPv6 loopback origins`() {
        featuresSupported(true)
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } returns Unit
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()

        // The IPv6 loopback is a normal local-development origin. It has to clear
        // the rule pattern to reach the loopback check, or the cleartext-http
        // allowance for ::1 never applies to the bridge at all.
        assertTrue(WebViewBridge.attach(webView, listOf("http://[::1]:3000"), processor))

        // A bracketed literal is still a host, not a licence to skip the format rules.
        val other = mockk<WebView>(relaxed = true)
        assertFalse(WebViewBridge.attach(other, listOf("http://[::1]:3000/path"), processor))
    }

    @Test
    fun `cleartext non-loopback origins warn but still attach`() {
        featuresSupported(true)
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } returns Unit
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any<String>()) } returns 0

        try {
            // A cleartext origin is spoofable in transit — accepted for flexibility,
            // but never silently.
            assertTrue(WebViewBridge.attach(webView, listOf("http://shop.example.com"), processor))
            verify {
                android.util.Log.w(any(), match<String> { it.contains("cleartext") })
            }

            val local = mockk<WebView>(relaxed = true)
            assertTrue(WebViewBridge.attach(local, listOf("http://localhost:3000"), processor))
            verify(exactly = 1) {
                android.util.Log.w(any(), match<String> { it.contains("cleartext") })
            }
        } finally {
            unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun `attach that throws in registration is caught and un-tracks the WebView`() {
        featuresSupported(true)
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } throws
            RuntimeException("platform rejected")
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()

        // No exception reaches the caller — the failure mode is no capture, logged.
        assertTrue(WebViewBridge.attach(webView, origins, processor))

        // The failed WebView was un-tracked, so a retry is not falsely rejected as
        // "already attached" — and this time registration succeeds.
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } returns Unit
        assertTrue(WebViewBridge.attach(webView, origins, processor))
        verify(exactly = 2) { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }
    }

    @Test
    fun `second attach on the same WebView is rejected`() {
        featuresSupported(true)
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } returns Unit
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()

        assertTrue(WebViewBridge.attach(webView, origins, processor))
        // Re-registering the same JS object name on a WebView throws in the platform;
        // the bridge must refuse instead of crashing the host.
        assertFalse(WebViewBridge.attach(webView, origins, processor))
        verify(exactly = 1) { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }
    }

    @Test
    fun `received message flows through the processor and acks over the reply proxy`() {
        featuresSupported(true)
        val listener = captureListener()

        val replyProxy = mockk<JavaScriptReplyProxy>(relaxed = true)
        val message = mockk<WebMessageCompat> {
            every { data } returns
                """{"version":1,"messageId":"m-1","type":"page","name":"page_view","properties":{}}"""
        }

        listener.captured.onPostMessage(webView, message, mockk(), true, replyProxy)

        assertEquals(1, sink.enqueued.size)
        assertEquals("page_view", sink.enqueued[0].name)
        verify { replyProxy.postMessage("""{"status":"ok","messageId":"m-1"}""") }
    }

    @Test
    fun `invalid message is rejected over the reply proxy and never enqueued`() {
        featuresSupported(true)
        val listener = captureListener()

        val replyProxy = mockk<JavaScriptReplyProxy>(relaxed = true)
        val message = mockk<WebMessageCompat> {
            every { data } returns "{not json"
        }

        listener.captured.onPostMessage(webView, message, mockk(), true, replyProxy)

        assertEquals(0, sink.enqueued.size)
        val replySlot = slot<String>()
        verify { replyProxy.postMessage(capture(replySlot)) }
        assertTrue(replySlot.captured.contains(""""code":"malformed_json""""))
    }

    @Test
    fun `null message data is ignored without a reply`() {
        featuresSupported(true)
        val listener = captureListener()

        val replyProxy = mockk<JavaScriptReplyProxy>(relaxed = true)
        val message = mockk<WebMessageCompat> {
            every { data } returns null
        }

        listener.captured.onPostMessage(webView, message, mockk(), true, replyProxy)

        assertEquals(0, sink.enqueued.size)
        verify(exactly = 0) { replyProxy.postMessage(any<String>()) }
    }

    private fun captureListener(): CapturingSlot<WebViewCompat.WebMessageListener> {
        val listener = slot<WebViewCompat.WebMessageListener>()
        every {
            WebViewCompat.addWebMessageListener(any(), any(), any(), capture(listener))
        } returns Unit
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()
        WebViewBridge.attach(webView, origins, processor)
        return listener
    }
}
