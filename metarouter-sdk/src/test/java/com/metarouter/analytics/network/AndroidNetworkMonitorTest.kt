package com.metarouter.analytics.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AndroidNetworkMonitorTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `onLost fires offline when lost network matches stale activeNetwork on WiFi-only device`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        val wifiNetwork = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns wifiNetwork
        every { cm.getNetworkCapabilities(wifiNetwork) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true

        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every {
            cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot))
        } just Runs

        val monitor = AndroidNetworkMonitor(context)
        var offlineCalls = 0
        var onlineCalls = 0
        monitor.start { connected ->
            if (connected) onlineCalls++ else offlineCalls++
        }

        // Simulate the race: WiFi is torn down, but cm.activeNetwork hasn't
        // been cleared yet — it still returns the dying network with INTERNET cap.
        cbSlot.captured.onLost(wifiNetwork)

        assertEquals("offline listener should fire exactly once", 1, offlineCalls)
        assertEquals("online listener should not fire", 0, onlineCalls)
        assertFalse("monitor should report disconnected", monitor.isConnected)
    }
}
