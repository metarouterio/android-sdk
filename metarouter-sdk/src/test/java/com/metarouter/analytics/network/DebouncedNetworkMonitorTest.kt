package com.metarouter.analytics.network

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedNetworkMonitorTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `online transition fires after debounce delay`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        var offlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++ else offlineCalled++
        }

        inner.setConnected(true)

        testScheduler.advanceTimeBy(1999)
        testScheduler.runCurrent()
        assertEquals(0, onlineCalled)

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(1, onlineCalled)
        assertEquals(0, offlineCalled)
    }

    @Test
    fun `online transition does not fire before debounce delay`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++
        }

        inner.setConnected(true)

        testScheduler.advanceTimeBy(1900)
        testScheduler.runCurrent()
        assertEquals(0, onlineCalled)
    }

    @Test
    fun `offline transition fires immediately`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = true)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var offlineCalled = 0
        monitor.start { connected ->
            if (!connected) offlineCalled++
        }

        inner.setConnected(false)
        assertEquals(1, offlineCalled)
    }

    @Test
    fun `rapid flapping produces single online action`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        var offlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++ else offlineCalled++
        }

        // Rapid flap: online -> offline -> online -> offline -> online
        inner.setConnected(true)
        inner.setConnected(false)
        inner.setConnected(true)
        inner.setConnected(false)
        inner.setConnected(true)

        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(1, onlineCalled)
        assertEquals(2, offlineCalled)
    }

    @Test
    fun `debounce timer cancelled on disconnect`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++
        }

        inner.setConnected(true)

        testScheduler.advanceTimeBy(1500)
        testScheduler.runCurrent()

        // Go offline — cancels pending debounce
        inner.setConnected(false)

        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(0, onlineCalled)
    }

    @Test
    fun `offline fires on each disconnect during flapping`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var offlineCalled = 0
        monitor.start { connected ->
            if (!connected) offlineCalled++
        }

        inner.setConnected(true)
        inner.setConnected(false)  // 1st offline
        inner.setConnected(true)
        inner.setConnected(false)  // 2nd offline

        assertEquals(2, offlineCalled)
    }

    @Test
    fun `clean transitions with gaps work independently`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        var offlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++ else offlineCalled++
        }

        // First online transition
        inner.setConnected(true)
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()
        assertEquals(1, onlineCalled)

        // Go offline
        inner.setConnected(false)
        assertEquals(1, offlineCalled)

        // Second online transition
        inner.setConnected(true)
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()
        assertEquals(2, onlineCalled)
    }

    @Test
    fun `stop cancels pending debounce`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++
        }

        inner.setConnected(true)
        monitor.stop()

        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(0, onlineCalled)
    }

    @Test
    fun `second online restarts debounce window`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++
        }

        inner.setConnected(true)

        // Advance 1.5s
        testScheduler.advanceTimeBy(1500)
        testScheduler.runCurrent()

        // Send another online — restarts window
        // FakeNetworkMonitor won't fire if state hasn't changed, so go offline then online
        inner.setConnected(false)
        inner.setConnected(true)

        // Advance 1.5s from second signal (3s total)
        testScheduler.advanceTimeBy(1500)
        testScheduler.runCurrent()
        assertEquals(0, onlineCalled) // Still within restarted window

        // Advance remaining 0.5s
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(1, onlineCalled)
    }

    @Test
    fun `no behavioral change on clean single transition`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = true)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var onlineCalled = 0
        var offlineCalled = 0
        monitor.start { connected ->
            if (connected) onlineCalled++ else offlineCalled++
        }

        inner.setConnected(false)
        assertEquals(1, offlineCalled)

        inner.setConnected(true)
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(1, onlineCalled)
        assertEquals(1, offlineCalled)
    }

    @Test
    fun `isConnected reflects debounced state`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = false)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        // Initially matches inner monitor
        assertFalse(monitor.isConnected)

        monitor.start { }

        // Inner goes online, but decorator hasn't debounced yet
        inner.setConnected(true)
        assertFalse(monitor.isConnected)

        // After debounce, decorator reflects online
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()
        assertTrue(monitor.isConnected)

        // Offline is immediate
        inner.setConnected(false)
        assertFalse(monitor.isConnected)
    }

    @Test
    fun `stop also stops inner monitor`() = runTest {
        val inner = FakeNetworkMonitor(initialConnected = true)
        val monitor = DebouncedNetworkMonitor(inner, scope = this)

        var callbackCount = 0
        monitor.start { callbackCount++ }

        monitor.stop()

        // Inner monitor's listener should be cleared — no callbacks after stop
        inner.setConnected(false)
        assertEquals(0, callbackCount)
    }
}
