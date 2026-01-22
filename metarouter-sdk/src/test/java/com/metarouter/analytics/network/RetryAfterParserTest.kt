package com.metarouter.analytics.network

import org.junit.Test
import org.junit.Assert.*

class RetryAfterParserTest {

    @Test
    fun `returns null for empty headers`() {
        assertNull(parseRetryAfterMs(emptyMap()))
    }

    @Test
    fun `returns null for missing Retry-After header`() {
        val headers = mapOf("Content-Type" to "application/json")
        assertNull(parseRetryAfterMs(headers))
    }

    @Test
    fun `parses numeric seconds`() {
        val headers = mapOf("Retry-After" to "120")
        assertEquals(120_000L, parseRetryAfterMs(headers))
    }

    @Test
    fun `parses numeric seconds with whitespace`() {
        val headers = mapOf("Retry-After" to "  60  ")
        assertEquals(60_000L, parseRetryAfterMs(headers))
    }

    @Test
    fun `handles zero seconds`() {
        val headers = mapOf("Retry-After" to "0")
        assertEquals(0L, parseRetryAfterMs(headers))
    }

    @Test
    fun `case-insensitive header lookup`() {
        assertEquals(30_000L, parseRetryAfterMs(mapOf("retry-after" to "30")))
        assertEquals(30_000L, parseRetryAfterMs(mapOf("RETRY-AFTER" to "30")))
        assertEquals(30_000L, parseRetryAfterMs(mapOf("Retry-after" to "30")))
    }

    @Test
    fun `returns null for invalid value`() {
        val headers = mapOf("Retry-After" to "not-a-number")
        assertNull(parseRetryAfterMs(headers))
    }

    @Test
    fun `returns null for negative value`() {
        val headers = mapOf("Retry-After" to "-10")
        // Negative parsed as-is, but result should be 0 or null
        val result = parseRetryAfterMs(headers)
        assertTrue("Negative should result in 0 or null", result == null || result == 0L)
    }

    @Test
    fun `parses HTTP-date format RFC 7231`() {
        // Date in the future (we'll use a date format that parses correctly)
        // Note: This test may be flaky if the date is in the past
        val headers = mapOf("Retry-After" to "Wed, 21 Oct 2099 07:28:00 GMT")
        val result = parseRetryAfterMs(headers)
        assertNotNull("Should parse HTTP-date", result)
        assertTrue("Future date should have positive ms", result!! > 0)
    }

    @Test
    fun `returns 0 for past HTTP-date`() {
        val headers = mapOf("Retry-After" to "Wed, 21 Oct 2020 07:28:00 GMT")
        val result = parseRetryAfterMs(headers)
        // Past date should return 0 (not negative)
        assertTrue("Past date should return 0 or small value", result == null || result == 0L)
    }
}
