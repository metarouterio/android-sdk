package com.metarouter.analytics.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeReplyTest {

    @Test
    fun `ok reply serializes to contract shape`() {
        val json = BridgeReply.ok("m-1").toJson()

        assertEquals("""{"status":"ok","messageId":"m-1"}""", json)
    }

    @Test
    fun `error reply from Invalid carries code message and supportedVersion`() {
        val invalid = BridgeParseResult.Invalid(
            code = BridgeErrorCode.UNSUPPORTED_VERSION,
            message = "envelope version 2 > supported 1",
            messageId = "m-1",
            supportedVersion = 1
        )

        val json = BridgeReply.error(invalid).toJson()

        assertEquals(
            """{"status":"error","messageId":"m-1","code":"unsupported_version",""" +
                """"message":"envelope version 2 > supported 1","supportedVersion":1}""",
            json
        )
    }

    @Test
    fun `error reply omits null fields`() {
        val invalid = BridgeParseResult.Invalid(
            code = BridgeErrorCode.MALFORMED_JSON,
            message = "envelope is not valid JSON"
        )

        val json = BridgeReply.error(invalid).toJson()

        assertFalse("messageId should be omitted", json.contains("messageId"))
        assertFalse("supportedVersion should be omitted", json.contains("supportedVersion"))
        assertTrue(json.contains(""""code":"malformed_json"""))
    }

    @Test
    fun `standalone error factory uses wire code strings`() {
        val json = BridgeReply.error(BridgeErrorCode.NOT_READY, "SDK not initialized").toJson()

        assertTrue(json.contains(""""code":"not_ready"""))
        assertTrue(json.contains("""status":"error"""))
    }

    @Test
    fun `every error code has a stable wire string`() {
        val expected = mapOf(
            BridgeErrorCode.MALFORMED_JSON to "malformed_json",
            BridgeErrorCode.MALFORMED_PAYLOAD to "malformed_payload",
            BridgeErrorCode.MISSING_FIELD to "missing_field",
            BridgeErrorCode.UNKNOWN_TYPE to "unknown_type",
            BridgeErrorCode.UNSUPPORTED_VERSION to "unsupported_version",
            BridgeErrorCode.PAYLOAD_TOO_LARGE to "payload_too_large",
            BridgeErrorCode.NOT_READY to "not_ready"
        )

        assertEquals(expected.size, BridgeErrorCode.entries.size)
        expected.forEach { (code, wire) -> assertEquals(wire, code.wire) }
    }
}
