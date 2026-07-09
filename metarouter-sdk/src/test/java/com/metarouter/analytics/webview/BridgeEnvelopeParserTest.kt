package com.metarouter.analytics.webview

import com.metarouter.analytics.types.EventType
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEnvelopeParserTest {

    // Full envelopes exactly as the injected wrapper produces them, every field populated.
    private val validTrack = """
        {"version":1,"messageId":"9b2f2c4e-8b1a-4d7e-9c3f-2e6a1d5b8f01","type":"track","name":"flight_search","properties":{"origin":"DXB","destination":"LHR","cabin":"J"},"sentAt":"2026-07-09T14:03:22.114Z","page":{"url":"https://www.metarouter.com/search","title":"Search flights","referrer":"https://www.metarouter.com/"},"source":{"producer":"wrapper","wrapperVersion":"1.0.0"}}
    """.trimIndent()

    private val validPage = """
        {"version":1,"messageId":"c41d09aa-3e02-4f4b-b0d7-77f21c9d54c2","type":"page","name":"page_view","properties":{"language":"en"},"sentAt":"2026-07-09T14:02:59.801Z","page":{"url":"https://www.metarouter.com/booking","title":"Book a flight","referrer":"https://www.metarouter.com/search"},"source":{"producer":"wrapper","wrapperVersion":"1.0.0"}}
    """.trimIndent()

    private fun valid(result: BridgeParseResult): BridgeEnvelope {
        assertTrue("expected Valid, got $result", result is BridgeParseResult.Valid)
        return (result as BridgeParseResult.Valid).envelope
    }

    private fun invalid(result: BridgeParseResult): BridgeParseResult.Invalid {
        assertTrue("expected Invalid, got $result", result is BridgeParseResult.Invalid)
        return result as BridgeParseResult.Invalid
    }

    // ===== Valid envelopes =====

    @Test
    fun `valid track envelope parses with all fields`() {
        val envelope = valid(BridgeEnvelopeParser.parse(validTrack))

        assertEquals(1, envelope.version)
        assertEquals("9b2f2c4e-8b1a-4d7e-9c3f-2e6a1d5b8f01", envelope.messageId)
        assertEquals(EventType.TRACK, envelope.type)
        assertEquals("flight_search", envelope.name)
        assertEquals("DXB", envelope.properties["origin"]?.jsonPrimitive?.content)
        assertEquals("2026-07-09T14:03:22.114Z", envelope.sentAt)
        assertEquals("https://www.metarouter.com/search", envelope.page?.url)
        assertEquals("Search flights", envelope.page?.title)
        assertEquals("https://www.metarouter.com/", envelope.page?.referrer)
        assertEquals("wrapper", envelope.source?.producer)
        assertEquals("1.0.0", envelope.source?.wrapperVersion)
    }

    @Test
    fun `valid page envelope parses with page type`() {
        val envelope = valid(BridgeEnvelopeParser.parse(validPage))

        assertEquals(EventType.PAGE, envelope.type)
        assertEquals("page_view", envelope.name)
    }

    @Test
    fun `minimal envelope with only required fields parses`() {
        val envelope = valid(
            BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","type":"track","name":"x"}""")
        )

        assertEquals(emptyMap<String, Any>(), envelope.properties)
        assertNull(envelope.sentAt)
        assertNull(envelope.page)
        assertNull(envelope.source)
    }

    @Test
    fun `unknown fields are ignored (forward compatibility)`() {
        val envelope = valid(
            BridgeEnvelopeParser.parse(
                """{"version":1,"messageId":"m-1","type":"track","name":"x","futureField":{"a":1},"another":true}"""
            )
        )

        assertEquals("x", envelope.name)
    }

    @Test
    fun `optional fields with wrong JSON type are treated as absent`() {
        val envelope = valid(
            BridgeEnvelopeParser.parse(
                """{"version":1,"messageId":"m-1","type":"track","name":"x","page":"not-an-object","source":42,"sentAt":123}"""
            )
        )

        assertNull(envelope.page)
        assertNull(envelope.source)
        assertNull(envelope.sentAt)
    }

    // ===== Malformed input =====

    @Test
    fun `invalid JSON is rejected with malformed_json and no messageId`() {
        val error = invalid(BridgeEnvelopeParser.parse("{not json"))

        assertEquals(BridgeErrorCode.MALFORMED_JSON, error.code)
        assertNull(error.messageId)
    }

    @Test
    fun `non-object JSON is rejected with malformed_payload`() {
        val error = invalid(BridgeEnvelopeParser.parse("""["an","array"]"""))

        assertEquals(BridgeErrorCode.MALFORMED_PAYLOAD, error.code)
    }

    @Test
    fun `non-object properties is rejected with malformed_payload and echoes messageId`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","type":"track","name":"x","properties":"oops"}""")
        )

        assertEquals(BridgeErrorCode.MALFORMED_PAYLOAD, error.code)
        assertEquals("m-1", error.messageId)
    }

    @Test
    fun `non-integer version is rejected with malformed_payload`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":"one","messageId":"m-1","type":"track","name":"x"}""")
        )

        assertEquals(BridgeErrorCode.MALFORMED_PAYLOAD, error.code)
    }

    @Test
    fun `version below 1 is rejected with malformed_payload`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":0,"messageId":"m-1","type":"track","name":"x"}""")
        )

        assertEquals(BridgeErrorCode.MALFORMED_PAYLOAD, error.code)
    }

    // ===== Partial input (missing required fields) =====

    @Test
    fun `missing messageId is rejected with missing_field`() {
        val error = invalid(BridgeEnvelopeParser.parse("""{"version":1,"type":"track","name":"x"}"""))

        assertEquals(BridgeErrorCode.MISSING_FIELD, error.code)
    }

    @Test
    fun `blank messageId is rejected with missing_field`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":1,"messageId":"  ","type":"track","name":"x"}""")
        )

        assertEquals(BridgeErrorCode.MISSING_FIELD, error.code)
    }

    @Test
    fun `missing version is rejected with missing_field and echoes messageId`() {
        val error = invalid(BridgeEnvelopeParser.parse("""{"messageId":"m-1","type":"track","name":"x"}"""))

        assertEquals(BridgeErrorCode.MISSING_FIELD, error.code)
        assertEquals("m-1", error.messageId)
    }

    @Test
    fun `missing type is rejected with missing_field`() {
        val error = invalid(BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","name":"x"}"""))

        assertEquals(BridgeErrorCode.MISSING_FIELD, error.code)
    }

    @Test
    fun `missing name is rejected with missing_field`() {
        val error = invalid(BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","type":"track"}"""))

        assertEquals(BridgeErrorCode.MISSING_FIELD, error.code)
    }

    @Test
    fun `empty name is rejected with missing_field`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","type":"track","name":""}""")
        )

        assertEquals(BridgeErrorCode.MISSING_FIELD, error.code)
    }

    // ===== Type rule =====

    @Test
    fun `unknown type is rejected with unknown_type`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","type":"identify","name":"x"}""")
        )

        assertEquals(BridgeErrorCode.UNKNOWN_TYPE, error.code)
        assertEquals("m-1", error.messageId)
    }

    @Test
    fun `screen type is rejected in contract v1`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":1,"messageId":"m-1","type":"screen","name":"x"}""")
        )

        assertEquals(BridgeErrorCode.UNKNOWN_TYPE, error.code)
    }

    // ===== Version rule =====

    @Test
    fun `newer version is rejected with unsupported_version and supportedVersion`() {
        val error = invalid(
            BridgeEnvelopeParser.parse("""{"version":2,"messageId":"m-1","type":"track","name":"x"}""")
        )

        assertEquals(BridgeErrorCode.UNSUPPORTED_VERSION, error.code)
        assertEquals("m-1", error.messageId)
        assertEquals(BridgeEnvelopeParser.SUPPORTED_VERSION, error.supportedVersion)
    }

    // ===== Oversized input =====

    @Test
    fun `envelope over 64KB is rejected with payload_too_large`() {
        val padding = "a".repeat(BridgeEnvelopeParser.MAX_ENVELOPE_BYTES)
        val oversized = """{"version":1,"messageId":"m-1","type":"track","name":"x","properties":{"p":"$padding"}}"""

        val error = invalid(BridgeEnvelopeParser.parse(oversized))

        assertEquals(BridgeErrorCode.PAYLOAD_TOO_LARGE, error.code)
    }

    @Test
    fun `size limit is measured in UTF-8 bytes not chars`() {
        // Multi-byte characters: 22000 × 3-byte chars ≈ 66KB > 64KB limit, but only 22k chars.
        val padding = "€".repeat(22_000)
        val oversized = """{"version":1,"messageId":"m-1","type":"track","name":"x","properties":{"p":"$padding"}}"""

        val error = invalid(BridgeEnvelopeParser.parse(oversized))

        assertEquals(BridgeErrorCode.PAYLOAD_TOO_LARGE, error.code)
    }
}
