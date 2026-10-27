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
import org.junit.Assert.assertNull
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

    private fun wifiFixture(
        activeIsWifi: Boolean
    ): Triple<AndroidNetworkMonitor, ConnectivityManager, io.mockk.CapturingSlot<ConnectivityManager.NetworkCallback>> {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns activeIsWifi
        val defaultSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerDefaultNetworkCallback(capture(defaultSlot)) } just Runs
        return Triple(AndroidNetworkMonitor(context), cm, defaultSlot)
    }

    private fun capsWithWifi(wifi: Boolean): NetworkCapabilities = mockk {
        every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns wifi
    }

    @Test
    fun `isWifi is seeded synchronously at construction`() {
        val (monitor, _, _) = wifiFixture(activeIsWifi = true)
        assertEquals(true, monitor.isWifi)
    }

    @Test
    fun `isWifi follows default network switches`() {
        val (monitor, _, slot) = wifiFixture(activeIsWifi = true)
        monitor.start { }
        slot.captured.onCapabilitiesChanged(mockk(), capsWithWifi(false))
        assertEquals(false, monitor.isWifi)
        slot.captured.onCapabilitiesChanged(mockk(), capsWithWifi(true))
        assertEquals(true, monitor.isWifi)
        slot.captured.onLost(mockk())
        assertEquals(false, monitor.isWifi)
    }

    @Test
    fun `stop reports unknown and ignores a late callback`() {
        val (monitor, _, slot) = wifiFixture(activeIsWifi = true)
        monitor.start { }
        monitor.stop()
        assertNull(monitor.isWifi)
        slot.captured.onCapabilitiesChanged(mockk(), capsWithWifi(true))
        assertNull("a callback in flight at stop() must not restore the value", monitor.isWifi)
    }

    @Test
    fun `missing ConnectivityManager reports unknown wifi`() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        assertNull(AndroidNetworkMonitor(context).isWifi)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `API 23 re-reads the active network on connectivity callbacks`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        val wifi = mockk<Network>()
        val cellular = mockk<Network>()
        val wifiCaps = mockk<NetworkCapabilities> {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        }
        val cellCaps = mockk<NetworkCapabilities> {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        }
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns wifi
        every { cm.getNetworkCapabilities(wifi) } returns wifiCaps
        every { cm.getNetworkCapabilities(cellular) } returns cellCaps
        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot)) } just Runs

        val monitor = AndroidNetworkMonitor(context)
        monitor.start { }
        assertEquals(true, monitor.isWifi)

        // Wifi drops while cellular is already up: handoff, still online.
        every { cm.activeNetwork } returns cellular
        cbSlot.captured.onLost(wifi)
        assertEquals(false, monitor.isWifi)

        every { cm.activeNetwork } returns wifi
        cbSlot.captured.onAvailable(wifi)
        assertEquals(true, monitor.isWifi)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `API 23 wifi joining under cellular is picked up when it validates`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        val wifi = mockk<Network>()
        val cellular = mockk<Network>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns cellular
        every { cm.getNetworkCapabilities(cellular) } returns capsWithWifi(false).also {
            every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        }
        every { cm.getNetworkCapabilities(wifi) } returns capsWithWifi(true)
        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot)) } just Runs

        val monitor = AndroidNetworkMonitor(context)
        monitor.start { }

        // onAvailable fires before wifi is validated and made the default.
        cbSlot.captured.onAvailable(wifi)
        assertEquals(false, monitor.isWifi)

        // Validation arrives as a capabilities change; wifi is now the default.
        every { cm.activeNetwork } returns wifi
        cbSlot.captured.onCapabilitiesChanged(wifi, capsWithWifi(true))
        assertEquals(true, monitor.isWifi)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun `API 24 default switch delivered as onAvailable alone updates wifi`() {
        val (monitor, cm, slot) = wifiFixture(activeIsWifi = true)
        monitor.start { }
        val cellular = mockk<Network>()
        every { cm.getNetworkCapabilities(cellular) } returns capsWithWifi(false)
        slot.captured.onAvailable(cellular)
        assertEquals(false, monitor.isWifi)
    }

    @Test
    fun `failed default-callback registration falls back to re-reading`() {
        val (monitor, cm, _) = wifiFixture(activeIsWifi = true)
        every { cm.registerDefaultNetworkCallback(any()) } throws RuntimeException("TooManyRequests")
        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot)) } just Runs
        monitor.start { }

        val cellular = mockk<Network>()
        every { cm.activeNetwork } returns cellular
        every { cm.getNetworkCapabilities(cellular) } returns capsWithWifi(false)
        cbSlot.captured.onCapabilitiesChanged(cellular, capsWithWifi(false))
        assertEquals("must not freeze at the initial value", false, monitor.isWifi)
    }

    @Test
    fun `restart after stop re-reads wifi instead of staying unknown`() {
        val (monitor, _, _) = wifiFixture(activeIsWifi = true)
        monitor.start { }
        monitor.stop()
        assertNull(monitor.isWifi)
        monitor.start { }
        assertEquals(true, monitor.isWifi)
    }

    @Test
    fun `second start without stop unregisters the first callbacks`() {
        val (monitor, cm, _) = wifiFixture(activeIsWifi = true)
        every { cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } just Runs
        monitor.start { }
        monitor.start { }
        io.mockk.verify(exactly = 2) { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }
}
