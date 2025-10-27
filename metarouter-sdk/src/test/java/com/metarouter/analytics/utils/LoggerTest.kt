package com.metarouter.analytics.utils

import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LoggerTest {

    @Before
    fun setUp() {
        ShadowLog.clear()
        Logger.debugEnabled = false
    }

    @After
    fun tearDown() {
        Logger.debugEnabled = false
        ShadowLog.clear()
    }

    @Test
    fun `log does not output when debug disabled`() {
        Logger.debugEnabled = false
        Logger.log("test message")

        val logs = ShadowLog.getLogs()
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `log outputs when debug enabled`() {
        Logger.debugEnabled = true
        Logger.log("test message")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.DEBUG, logs[0].type)
        assertEquals("MetaRouter", logs[0].tag)
        assertEquals("test message", logs[0].msg)
    }

    @Test
    fun `warn always outputs regardless of debug setting`() {
        Logger.debugEnabled = false
        Logger.warn("warning message")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.WARN, logs[0].type)
        assertEquals("MetaRouter", logs[0].tag)
        assertEquals("warning message", logs[0].msg)
    }

    @Test
    fun `error always outputs regardless of debug setting`() {
        Logger.debugEnabled = false
        Logger.error("error message")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.ERROR, logs[0].type)
        assertEquals("MetaRouter", logs[0].tag)
        assertEquals("error message", logs[0].msg)
    }

    @Test
    fun `error with throwable outputs stack trace`() {
        Logger.debugEnabled = false
        val exception = RuntimeException("Test exception")
        Logger.error("error with exception", exception)

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.ERROR, logs[0].type)
        assertEquals("MetaRouter", logs[0].tag)
        assertEquals("error with exception", logs[0].msg)
        assertEquals(exception, logs[0].throwable)
    }

    @Test
    fun `maskWriteKey masks all but last 4 characters`() {
        assertEquals("***3xyz", Logger.maskWriteKey("abc123xyz"))
        assertEquals("***7890", Logger.maskWriteKey("1234567890"))
    }

    @Test
    fun `maskWriteKey masks short keys`() {
        assertEquals("***", Logger.maskWriteKey("abc"))
        assertEquals("***", Logger.maskWriteKey("ab"))
        assertEquals("***", Logger.maskWriteKey("a"))
        assertEquals("***", Logger.maskWriteKey(""))
    }

    @Test
    fun `maskWriteKey preserves last 4 for exactly 4 character key`() {
        assertEquals("***", Logger.maskWriteKey("abcd"))
    }

    @Test
    fun `redactPII shows first 8 characters`() {
        assertEquals("12345678***", Logger.redactPII("12345678-1234-1234-1234-123456789abc"))
        assertEquals("abcdefgh***", Logger.redactPII("abcdefghijklmnop"))
    }

    @Test
    fun `redactPII masks short values`() {
        assertEquals("***", Logger.redactPII("short"))
        assertEquals("***", Logger.redactPII("1234567"))
        assertEquals("***", Logger.redactPII(""))
    }

    @Test
    fun `logWithContext outputs when debug enabled`() {
        Logger.debugEnabled = true
        Logger.logWithContext("Context message", "my-write-key", "https://example.com")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.DEBUG, logs[0].type)
        assertEquals("MetaRouter", logs[0].tag)
        assertTrue(logs[0].msg.contains("Context message"))
        assertTrue(logs[0].msg.contains("writeKey=***-key"))
        assertTrue(logs[0].msg.contains("host=https://example.com"))
    }

    @Test
    fun `logWithContext does not output when debug disabled`() {
        Logger.debugEnabled = false
        Logger.logWithContext("Context message", "my-write-key", "https://example.com")

        val logs = ShadowLog.getLogs()
        assertTrue(logs.isEmpty())
    }
}
