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
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AndroidNetworkMonitorTest {

    private companion object {
        const val WIFI = 100
        const val CELLULAR = 101
        const val ETHERNET = 102
    }

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
    fun `losing the only network goes offline even while activeNetwork still reads it`() {
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

        cbSlot.captured.onAvailable(wifiNetwork)
        // Simulate the race: WiFi is torn down, but cm.activeNetwork hasn't
        // been cleared yet — it still returns the dying network with INTERNET cap.
        cbSlot.captured.onLost(wifiNetwork)

        assertEquals("offline listener should fire exactly once", 1, offlineCalls)
        assertEquals("online listener should not fire", 0, onlineCalls)
        assertFalse("monitor should report disconnected", monitor.isConnected)
    }

    /**
     * Wifi drops while cellular was reported earlier. activeNetwork reads null (or the
     * lost wifi) mid-handoff, and cellular gets no new onAvailable, so deciding from
     * activeNetwork left the SDK offline until the next network appeared. Each callback
     * gets its own Network object, as unparcelled on a device; they match by netId.
     */
    @Test
    fun `wifi lost with cellular already reported stays online`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns null
        every { cm.getNetworkCapabilities(any()) } returns mockk(relaxed = true)
        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot)) } just Runs

        val monitor = AndroidNetworkMonitor(context)
        var offlineCalls = 0
        monitor.start { connected -> if (!connected) offlineCalls++ }
        cbSlot.captured.onAvailable(ShadowNetwork.newInstance(WIFI))
        cbSlot.captured.onAvailable(ShadowNetwork.newInstance(CELLULAR))

        cbSlot.captured.onLost(ShadowNetwork.newInstance(WIFI))
        assertEquals(true, monitor.isConnected)
        assertEquals(0, offlineCalls)

        cbSlot.captured.onLost(ShadowNetwork.newInstance(CELLULAR))
        assertFalse(monitor.isConnected)
        assertEquals("offline listener fires once, on the last loss", 1, offlineCalls)
    }

    /** A network whose onLost never arrived reads no capabilities; it doesn't keep the SDK online. */
    @Test
    fun `a network lost without its own onLost is dropped at the next loss`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        val wifi = ShadowNetwork.newInstance(WIFI)
        val cellular = ShadowNetwork.newInstance(CELLULAR)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns null
        every { cm.getNetworkCapabilities(any()) } returns mockk(relaxed = true)
        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot)) } just Runs

        val monitor = AndroidNetworkMonitor(context)
        monitor.start { }
        cbSlot.captured.onAvailable(wifi)
        cbSlot.captured.onAvailable(cellular)
        every { cm.getNetworkCapabilities(cellular) } returns null  // gone, onLost dropped

        cbSlot.captured.onLost(wifi)

        assertFalse(monitor.isConnected)
    }

    /** A restart gets a fresh set; a late callback from the old registration can't touch it. */
    @Test
    fun `restart forgets networks from the previous registration`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns null
        every { cm.getNetworkCapabilities(any()) } returns mockk(relaxed = true)
        val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(callbacks)) } just Runs

        val monitor = AndroidNetworkMonitor(context)
        monitor.start { }
        val old = callbacks.last()
        old.onAvailable(ShadowNetwork.newInstance(CELLULAR))
        monitor.start { }  // second start without stop
        val current = callbacks.last()
        old.onAvailable(ShadowNetwork.newInstance(ETHERNET))  // in flight at unregister
        current.onAvailable(ShadowNetwork.newInstance(WIFI))
        current.onLost(ShadowNetwork.newInstance(WIFI))

        assertFalse("a network from the old registration kept it online", monitor.isConnected)
    }

    /**
     * A loss for a network this registration never reported is stale, not a disconnect:
     * e.g. one from an unregistered callback, arriving before the new one reports anything.
     */
    @Test
    fun `losing a network that was never reported changes nothing`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        val wifi = ShadowNetwork.newInstance(WIFI)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns wifi  // seeds online
        every { cm.getNetworkCapabilities(wifi) } returns mockk(relaxed = true) {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        }
        val cbSlot = slot<ConnectivityManager.NetworkCallback>()
        every { cm.registerNetworkCallback(any<NetworkRequest>(), capture(cbSlot)) } just Runs

        val monitor = AndroidNetworkMonitor(context)
        var offlineCalls = 0
        monitor.start { connected -> if (!connected) offlineCalls++ }
        cbSlot.captured.onLost(ShadowNetwork.newInstance(CELLULAR))

        assertEquals(true, monitor.isConnected)
        assertEquals(0, offlineCalls)
    }

    /** Registration can throw past SecurityException (TooManyRequestsException on API 30+). */
    @Test
    fun `a failed registration does not fail start`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns null  // startup read says offline
        every { cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } throws RuntimeException("too many requests")

        val monitor = AndroidNetworkMonitor(context)
        assertFalse(monitor.isConnected)
        var onlineCalls = 0
        monitor.start { connected -> if (connected) onlineCalls++ }

        // No callback will ever arrive, so the startup read must not stick.
        assertEquals(true, monitor.isConnected)
        assertEquals(1, onlineCalls)
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
    fun `API 23 reads wifi from the networks left after a loss`() {
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
        cbSlot.captured.onAvailable(wifi)
        cbSlot.captured.onAvailable(cellular)
        assertEquals(true, monitor.isWifi)

        // Wifi drops while cellular is already up: handoff, still online. activeNetwork
        // still reads the lost wifi, so wifi comes from the networks left.
        every { cm.activeNetwork } returns wifi
        cbSlot.captured.onLost(wifi)
        every { cm.activeNetwork } returns cellular
        assertEquals(false, monitor.isWifi)
        assertEquals(true, monitor.isConnected)

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
