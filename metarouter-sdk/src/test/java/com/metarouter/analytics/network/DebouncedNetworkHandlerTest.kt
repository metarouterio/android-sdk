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
class DebouncedNetworkHandlerTest {

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
        var onlineCalled = 0
        var offlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = { offlineCalled++ }
        )

        handler.onConnectivityChanged(true)

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
        var onlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = {}
        )

        handler.onConnectivityChanged(true)

        testScheduler.advanceTimeBy(1900)
        testScheduler.runCurrent()
        assertEquals(0, onlineCalled)
    }

    @Test
    fun `offline transition fires immediately`() = runTest {
        var offlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = {},
            onOffline = { offlineCalled++ }
        )

        handler.onConnectivityChanged(false)
        assertEquals(1, offlineCalled)
    }

    @Test
    fun `rapid flapping produces single online action`() = runTest {
        var onlineCalled = 0
        var offlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = { offlineCalled++ }
        )

        // Rapid flap: online → offline → online → offline → online
        handler.onConnectivityChanged(true)
        handler.onConnectivityChanged(false)
        handler.onConnectivityChanged(true)
        handler.onConnectivityChanged(false)
        handler.onConnectivityChanged(true)

        // Advance past debounce
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(1, onlineCalled)
        assertEquals(2, offlineCalled)
    }

    @Test
    fun `debounce timer cancelled on disconnect`() = runTest {
        var onlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = {}
        )

        handler.onConnectivityChanged(true)

        // Advance partway through debounce
        testScheduler.advanceTimeBy(1500)
        testScheduler.runCurrent()

        // Go offline — cancels pending debounce
        handler.onConnectivityChanged(false)

        // Advance past original debounce window
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(0, onlineCalled)
    }

    @Test
    fun `offline fires on each disconnect during flapping`() = runTest {
        var offlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = {},
            onOffline = { offlineCalled++ }
        )

        handler.onConnectivityChanged(true)
        handler.onConnectivityChanged(false)  // 1st offline
        handler.onConnectivityChanged(true)
        handler.onConnectivityChanged(false)  // 2nd offline

        assertEquals(2, offlineCalled)
    }

    @Test
    fun `clean transitions with gaps work independently`() = runTest {
        var onlineCalled = 0
        var offlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = { offlineCalled++ }
        )

        // First online transition
        handler.onConnectivityChanged(true)
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()
        assertEquals(1, onlineCalled)

        // Go offline
        handler.onConnectivityChanged(false)
        assertEquals(1, offlineCalled)

        // Second online transition
        handler.onConnectivityChanged(true)
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()
        assertEquals(2, onlineCalled)
    }

    @Test
    fun `cancel stops pending debounce`() = runTest {
        var onlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = {}
        )

        handler.onConnectivityChanged(true)
        handler.cancel()

        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(0, onlineCalled)
    }

    @Test
    fun `second online restarts debounce window`() = runTest {
        var onlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = {}
        )

        handler.onConnectivityChanged(true)

        // Advance 1.5s
        testScheduler.advanceTimeBy(1500)
        testScheduler.runCurrent()

        // Send another online — restarts window
        handler.onConnectivityChanged(true)

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
        var onlineCalled = 0
        var offlineCalled = 0
        val handler = DebouncedNetworkHandler(
            scope = this,
            onOnline = { onlineCalled++ },
            onOffline = { offlineCalled++ }
        )

        handler.onConnectivityChanged(false)
        assertEquals(1, offlineCalled)

        handler.onConnectivityChanged(true)
        testScheduler.advanceTimeBy(2000)
        testScheduler.runCurrent()

        assertEquals(1, onlineCalled)
        assertEquals(1, offlineCalled)
    }
}
