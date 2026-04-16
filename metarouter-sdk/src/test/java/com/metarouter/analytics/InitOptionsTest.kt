package com.metarouter.analytics

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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

    // ===== maxDiskEvents =====

    @Test
    fun `maxDiskEvents defaults to 10000`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com"
        )

        assertEquals(10000, options.maxDiskEvents)
    }

    @Test
    fun `custom maxDiskEvents accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxDiskEvents = 5000
        )

        assertEquals(5000, options.maxDiskEvents)
    }

    @Test
    fun `zero maxDiskEvents is accepted as in-memory-only opt-out`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxDiskEvents = 0
        )

        assertEquals(0, options.maxDiskEvents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative maxDiskEvents throws exception`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxDiskEvents = -1
        )
    }

    // ===== maxDiskEvents vs maxQueueEvents mismatch warning =====

    @Test
    fun `warns when maxDiskEvents is less than maxQueueEvents`() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        try {
            InitOptions(
                writeKey = "test-key",
                ingestionHost = "https://example.com",
                maxQueueEvents = 2000,
                maxDiskEvents = 500
            )
            verify {
                Log.w(
                    any(),
                    match<String> {
                        it.contains("maxDiskEvents (500)") &&
                            it.contains("maxQueueEvents (2000)") &&
                            it.contains("dropped")
                    }
                )
            }
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `does not warn when maxDiskEvents equals maxQueueEvents`() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        try {
            InitOptions(
                writeKey = "test-key",
                ingestionHost = "https://example.com",
                maxQueueEvents = 2000,
                maxDiskEvents = 2000
            )
            verify(exactly = 0) { Log.w(any(), any<String>()) }
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `does not warn when maxDiskEvents exceeds maxQueueEvents`() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        try {
            InitOptions(
                writeKey = "test-key",
                ingestionHost = "https://example.com",
                maxQueueEvents = 2000,
                maxDiskEvents = 10000
            )
            verify(exactly = 0) { Log.w(any(), any<String>()) }
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `does not warn when maxDiskEvents is zero (in-memory-only opt-out)`() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        try {
            InitOptions(
                writeKey = "test-key",
                ingestionHost = "https://example.com",
                maxQueueEvents = 2000,
                maxDiskEvents = 0
            )
            verify(exactly = 0) { Log.w(any(), any<String>()) }
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
