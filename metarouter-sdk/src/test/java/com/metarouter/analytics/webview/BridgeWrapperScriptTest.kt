package com.metarouter.analytics.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWrapperScriptTest {

    private val origins = listOf("https://www.metarouter.com")

    @Test
    fun `script defines the default bridge object with track and page`() {
        val script = BridgeWrapperScript.build(origins)

        assertTrue(script.contains("window.metarouterBridge = {"))
        assertTrue(script.contains("track: function(name, properties)"))
        assertTrue(script.contains("page: function(name, properties)"))
    }

    @Test
    fun `script embeds the origin allowlist as a JSON array`() {
        val script = BridgeWrapperScript.build(
            listOf("https://www.metarouter.com", "https://booking.metarouter.com")
        )

        assertTrue(
            script.contains("""var ALLOWED_ORIGINS = ["https://www.metarouter.com","https://booking.metarouter.com"];""")
        )
        assertTrue(script.contains("ALLOWED_ORIGINS.indexOf(location.origin) === -1"))
    }

    @Test
    fun `script posts through the native channel object`() {
        val script = BridgeWrapperScript.build(origins)

        assertTrue(script.contains("window.${BridgeWrapperScript.NATIVE_CHANNEL_NAME}"))
        assertTrue(script.contains("channel.postMessage(JSON.stringify(envelope))"))
    }

    @Test
    fun `script stamps envelope version and wrapper version`() {
        val script = BridgeWrapperScript.build(origins)

        assertTrue(script.contains("version: 1,"))
        assertTrue(script.contains("wrapperVersion: '${BridgeWrapperScript.WRAPPER_VERSION}'"))
    }

    @Test
    fun `script guards against double definition`() {
        val script = BridgeWrapperScript.build(origins)

        assertTrue(script.contains("if (window.metarouterBridge) { return; }"))
    }

    @Test
    fun `custom bridge object name is honored everywhere`() {
        val script = BridgeWrapperScript.build(origins, bridgeObjectName = "customBridge")

        assertTrue(script.contains("window.customBridge = {"))
        assertTrue(script.contains("if (window.customBridge) { return; }"))
        assertFalse(script.contains("metarouterBridge"))
    }

    @Test
    fun `origins containing quotes are escaped safely`() {
        // A hostile origin string must not be able to break out of the JS string literal:
        // the embedded quote must appear escaped (\"), never as a bare closing quote.
        val script = BridgeWrapperScript.build(listOf("""https://x.com/"};alert(1);//"""))

        assertTrue("quote must be escaped", script.contains("""/\"};alert(1);//"""))
        assertFalse("bare quote would terminate the JS string", script.contains("""/"};alert(1);//"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty origin list is rejected`() {
        BridgeWrapperScript.build(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid JS identifier for bridge object name is rejected`() {
        BridgeWrapperScript.build(origins, bridgeObjectName = "bad-name;alert(1)")
    }

    @Test
    fun `wrapper envelope round-trips through the native parser`() {
        // Build the envelope shape the wrapper's post() constructs and confirm the
        // native parser accepts it — keeps script and parser from drifting apart.
        val envelope = """
            {"version":1,"messageId":"11111111-2222-4333-8444-555555555555","type":"track",
             "name":"flight_search","properties":{"origin":"DXB"},
             "sentAt":"2026-07-09T14:03:22.114Z",
             "page":{"url":"https://www.metarouter.com/search","title":"Search","referrer":""},
             "source":{"producer":"wrapper","wrapperVersion":"${BridgeWrapperScript.WRAPPER_VERSION}"}}
        """.trimIndent()

        val result = BridgeEnvelopeParser.parse(envelope)

        assertTrue("wrapper-shaped envelope must parse", result is BridgeParseResult.Valid)
        assertEquals(
            BridgeWrapperScript.WRAPPER_VERSION,
            (result as BridgeParseResult.Valid).envelope.source?.wrapperVersion
        )
    }
}
