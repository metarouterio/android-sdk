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

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `valid options are accepted with no config error`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com"
        )

        assertEquals("test-key", options.writeKey)
        assertEquals("https://example.com", options.ingestionHost)
        assertEquals(10, options.flushIntervalSeconds)
        assertEquals(false, options.debug)
        assertEquals(2000, options.maxQueueEvents)
        assertNull(options.configError)
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
        assertNull(options.configError)
    }

    // ===== Invalid-config contract: record instead of throw =====

    @Test
    fun `empty writeKey records a config error instead of throwing`() {
        val options = InitOptions(
            writeKey = "",
            ingestionHost = "https://example.com"
        )

        assertEquals(ConfigError.EmptyWriteKey, options.configError)
    }

    @Test
    fun `whitespace-only writeKey is rejected`() {
        val options = InitOptions(
            writeKey = "   ",
            ingestionHost = "https://example.com"
        )

        assertEquals(ConfigError.EmptyWriteKey, options.configError)
    }

    @Test
    fun `writeKey is stored trimmed`() {
        val options = InitOptions(
            writeKey = "  wk  ",
            ingestionHost = "https://example.com"
        )

        assertEquals("wk", options.writeKey)
        assertNull(options.configError)
    }

    @Test
    fun `empty ingestionHost records invalid host`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = ""
        )

        assertEquals(ConfigError.InvalidIngestionHost(""), options.configError)
    }

    @Test
    fun `unparseable ingestionHost records the original string`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "not-a-url"
        )

        assertEquals(ConfigError.InvalidIngestionHost("not-a-url"), options.configError)
    }

    @Test
    fun `non-http scheme records invalid host`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "ftp://example.com"
        )

        assertEquals(ConfigError.InvalidIngestionHost("ftp://example.com"), options.configError)
    }

    @Test
    fun `hostless URL records invalid host`() {
        // "https://" becomes "https:" after the slash trim, which parses as a scheme
        // with no host — accepting it would build a client that fails every request
        // at network time.
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://"
        )

        assertEquals(ConfigError.InvalidIngestionHost("https://"), options.configError)
    }

    @Test
    fun `scheme check is case-insensitive`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "HTTPS://example.com"
        )

        assertNull(options.configError)
    }

    @Test
    fun `first error wins - writeKey before host`() {
        val options = InitOptions(
            writeKey = "",
            ingestionHost = "ftp://example.com"
        )

        assertEquals(ConfigError.EmptyWriteKey, options.configError)
    }

    @Test
    fun `trailing slash is trimmed not rejected`() {
        // Previously a hard rejection; a cosmetic slash must never disable analytics.
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com/"
        )

        assertNull(options.configError)
        assertEquals("https://example.com", options.ingestionHost)
    }

    @Test
    fun `config error descriptions are stable`() {
        // The description is what reaches the host's log and diagnostics — pinned so
        // it stays byte-identical with the iOS SDK's wording.
        assertEquals(
            "writeKey must not be empty or whitespace-only",
            ConfigError.EmptyWriteKey.description
        )
        assertEquals(
            "ingestionHost must be an http(s) URL, got \"nope\"",
            ConfigError.InvalidIngestionHost("nope").description
        )
    }

    // ===== Numeric bounds: one clamp-with-warning policy =====

    @Test
    fun `zero flushIntervalSeconds clamps to 1 with a warning`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            flushIntervalSeconds = 0
        )

        assertNull(options.configError)
        assertEquals(1, options.flushIntervalSeconds)
        verify { Log.w(any(), match<String> { it.contains("flushIntervalSeconds") && it.contains("clamped") }) }
    }

    @Test
    fun `negative maxQueueEvents clamps to 1 with a warning`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = -1
        )

        assertNull(options.configError)
        assertEquals(1, options.maxQueueEvents)
        verify { Log.w(any(), match<String> { it.contains("maxQueueEvents") && it.contains("clamped") }) }
    }

    @Test
    fun `negative maxDiskEvents clamps to 0 with a warning`() {
        // Previously a hard rejection; now the same clamp-with-warn policy as the
        // other numeric fields.
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxDiskEvents = -5
        )

        assertNull(options.configError)
        assertEquals(0, options.maxDiskEvents)
        verify { Log.w(any(), match<String> { it.contains("maxDiskEvents") && it.contains("clamped") }) }
    }

    @Test
    fun `zero sessionTimeoutMinutes clamps to 1 with a warning`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            sessionTimeoutMinutes = 0
        )

        assertNull(options.configError)
        assertEquals(1, options.sessionTimeoutMinutes)
        verify { Log.w(any(), match<String> { it.contains("sessionTimeoutMinutes") && it.contains("clamped") }) }
    }

    @Test
    fun `session defaults match web parity`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com"
        )

        assertEquals("web session default is 30 minutes", 30, options.sessionTimeoutMinutes)
        assertEquals(
            "off by default so upgrading never changes a customer's event volume",
            false, options.fireSessionStarted
        )
    }

    @Test
    fun `copy preserves session fields`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            sessionTimeoutMinutes = 45,
            fireSessionStarted = true
        )

        val copied = options.copy(debug = true)

        assertEquals(45, copied.sessionTimeoutMinutes)
        assertEquals(true, copied.fireSessionStarted)
    }

    // ===== Cleartext http policy =====

    @Test
    fun `cleartext http host warns but is accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "http://api.example.com"
        )

        assertNull(options.configError)
        verify { Log.w(any(), match<String> { it.contains("cleartext") }) }
    }

    @Test
    fun `loopback http hosts do not warn`() {
        InitOptions(writeKey = "test-key", ingestionHost = "http://localhost:8080")
        InitOptions(writeKey = "test-key", ingestionHost = "http://127.0.0.1:9999")
        InitOptions(writeKey = "test-key", ingestionHost = "http://[::1]:9999")

        verify(exactly = 0) { Log.w(any(), match<String> { it.contains("cleartext") }) }
    }

    // ===== Normalization accessors =====

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

    // ===== copy =====

    @Test
    fun `copy preserves values and re-validates the changed field`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = 5000
        )

        val copied = options.copy(writeKey = "other-key")
        assertEquals("other-key", copied.writeKey)
        assertEquals("https://example.com", copied.ingestionHost)
        assertEquals(5000, copied.maxQueueEvents)
        assertNull(copied.configError)

        val invalid = options.copy(writeKey = " ")
        assertEquals(ConfigError.EmptyWriteKey, invalid.configError)
    }

    @Test
    fun `discardingConfigCallback drops only the callback`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            onConfigError = { }
        )

        val stripped = options.discardingConfigCallback()
        assertNull(stripped.onConfigError)
        assertEquals(options.writeKey, stripped.writeKey)
        assertEquals(options.ingestionHost, stripped.ingestionHost)
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

    // ===== maxDiskEvents vs maxQueueEvents mismatch warning =====

    @Test
    fun `warns when maxDiskEvents is less than maxQueueEvents`() {
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
    }

    @Test
    fun `does not warn when maxDiskEvents equals maxQueueEvents`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = 2000,
            maxDiskEvents = 2000
        )

        verify(exactly = 0) { Log.w(any(), any<String>()) }
    }

    @Test
    fun `does not warn when maxDiskEvents exceeds maxQueueEvents`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = 2000,
            maxDiskEvents = 10000
        )

        verify(exactly = 0) { Log.w(any(), any<String>()) }
    }

    @Test
    fun `does not warn when maxDiskEvents is zero (in-memory-only opt-out)`() {
        InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            maxQueueEvents = 2000,
            maxDiskEvents = 0
        )

        verify(exactly = 0) { Log.w(any(), any<String>()) }
    }

    // ===== trackLifecycleEvents =====

    @Test
    fun `trackLifecycleEvents defaults to false (opt-in)`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com"
        )

        assertEquals(false, options.trackLifecycleEvents)
    }

    @Test
    fun `trackLifecycleEvents explicit true is accepted`() {
        val options = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://example.com",
            trackLifecycleEvents = true
        )

        assertEquals(true, options.trackLifecycleEvents)
    }
}
