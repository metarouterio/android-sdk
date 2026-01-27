package com.metarouter.analytics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InitOptionsTest {

    @Test
    fun `valid options are accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com"
        )

        assertEquals("test-key", options.writeKey)
        assertEquals("https://example.com", options.ingestionHost)
        assertEquals(10, options.flushIntervalSeconds)
        assertEquals(false, options.debug)
        assertEquals(2000, options.maxQueueEvents)
    }

    @Test
    fun `custom values are accepted`() {
        val options = InitOptions(
            writeKey = "custom-key",
            ingestionHost = "https://api.example.com",
            flushIntervalSeconds = 30,
            debug = true,
            maxQueueEvents = 5000
        )

        assertEquals("custom-key", options.writeKey)
        assertEquals("https://api.example.com", options.ingestionHost)
        assertEquals(30, options.flushIntervalSeconds)
        assertEquals(true, options.debug)
        assertEquals(5000, options.maxQueueEvents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty writeKey throws exception`() {
        InitOptions(
            writeKey = "",
            ingestionHost = "https://example.com"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `whitespace-only writeKey throws exception`() {
        InitOptions(
            writeKey = "   ",
            ingestionHost = "https://example.com"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty ingestionHost throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ingestionHost with trailing slash throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com/"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid URL for ingestionHost throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "not-a-url"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-http protocol throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "ftp://example.com"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero flushIntervalSeconds throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            flushIntervalSeconds = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative flushIntervalSeconds throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            flushIntervalSeconds = -1
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero maxQueueEvents throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative maxQueueEvents throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = -1
        )
    }

    @Test
    fun `getNormalizedIngestionHost returns host without trailing slash`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com"
        )

        assertEquals("https://example.com", options.getNormalizedIngestionHost())
    }

    @Test
    fun `getFlushIntervalMillis converts seconds to milliseconds`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            flushIntervalSeconds = 10
        )

        assertEquals(10000L, options.getFlushIntervalMillis())
    }

    @Test
    fun `http protocol is accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "http://localhost:8080"
        )

        assertEquals("http://localhost:8080", options.ingestionHost)
    }

    @Test
    fun `https protocol is accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://api.example.com"
        )

        assertEquals("https://api.example.com", options.ingestionHost)
    }

    @Test
    fun `ingestionHost with port is accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com:8443"
        )

        assertEquals("https://example.com:8443", options.ingestionHost)
    }

    @Test
    fun `ingestionHost with path is accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com/api/v1"
        )

        assertEquals("https://example.com/api/v1", options.ingestionHost)
    }

    @Test
    fun `writeKey with leading and trailing whitespace is trimmed during validation`() {
        val exception = try {
            InitOptions(
                writeKey = "  ",
                ingestionHost = "https://example.com"
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull(exception)
        assertTrue(exception?.message?.contains("writeKey") == true)
    }
}
