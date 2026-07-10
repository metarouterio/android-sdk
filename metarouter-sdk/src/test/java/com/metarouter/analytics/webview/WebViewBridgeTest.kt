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
        override fun enqueue(envelope: BridgeEnvelope) {
            enqueued.add(envelope)
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
