package com.metarouter.analytics.enrichment

import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.consent.ConsentStorage
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.network.FakeNetworkMonitor
import com.metarouter.analytics.network.NetworkMonitor
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.DeviceContext
import com.metarouter.analytics.types.EventContext
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LibraryContext
import com.metarouter.analytics.types.NetworkContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The context freshness contract: static fields are cached once, `network` is read
 * live per event, and `device.advertisingId` is overlaid per event. Mirrors iOS
 * ContextFreshnessTests so the two SDKs cannot drift on it silently.
 */
@RunWith(RobolectricTestRunner::class)
class ContextFreshnessTest {

    private val identityManager: IdentityManager = mockk(relaxed = true) {
        coEvery { getAnonymousId() } returns "anon"
        coEvery { getUserId() } returns null
        coEvery { getGroupId() } returns null
        coEvery { getAdvertisingId() } returns null
    }

    private fun provider(network: NetworkContext? = null): DeviceContextProvider = mockk {
        every { getContext() } returns EventContext(
            library = LibraryContext(name = "t", version = "1"),
            device = DeviceContext(manufacturer = "Google", model = "Pixel 8", name = "shiba", type = "android"),
            network = network
        )
    }

    private fun service(
        monitor: NetworkMonitor?,
        contextProvider: DeviceContextProvider = provider()
    ) = EventEnrichmentService(
        identityManager = identityManager,
        contextProvider = contextProvider,
        writeKey = "wk",
        sessionManager = SessionManager(
            storage = mockk<SessionStorage>(relaxUnitFun = true) {
                every { getSessionId() } returns null
                every { getSessionCount() } returns null
                every { getLastActivityMs() } returns null
            },
            wallClockMillis = { 1_757_400_000_000L },
            monotonicClockMillis = { 50_000L }
        ),
        consentManager = ConsentManager(
            mockk<ConsentStorage>(relaxUnitFun = true) { every { get() } returns null },
            dispatch = { }
        ),
        providerContextManager = ProviderContextManager(),
        networkMonitor = monitor
    )

    private suspend fun EventEnrichmentService.track(name: String) =
        enrichEvent(BaseEvent(type = EventType.TRACK, event = name))

    // network

    @Test
    fun `network wifi tracks interface switch on next event`() = runBlocking {
        val monitor = FakeNetworkMonitor(initialWifi = true)
        val service = service(monitor)

        assertEquals(true, service.track("a").context.network?.wifi)
        monitor.isWifi = false
        assertEquals(false, service.track("b").context.network?.wifi)
        monitor.isWifi = true
        assertEquals(true, service.track("c").context.network?.wifi)
    }

    @Test
    fun `unknown interface drops the network object instead of wifi null`() = runBlocking {
        val payload = service(FakeNetworkMonitor(initialWifi = null)).track("a")
        assertNull("spec: wifi must be a boolean when network is present", payload.context.network)
    }

    @Test
    fun `unknown interface keeps a provider-supplied network`() = runBlocking {
        val payload = service(
            FakeNetworkMonitor(initialWifi = null),
            provider(network = NetworkContext(wifi = true))
        ).track("a")
        assertEquals(true, payload.context.network?.wifi)
    }

    @Test
    fun `monitor without wifi tracking defaults to unknown`() {
        val legacy = object : NetworkMonitor {
            override val isConnected = true
            override fun start(onConnectivityChanged: (isConnected: Boolean) -> Unit) {}
            override fun stop() {}
        }
        assertNull(legacy.isWifi)
    }

    // advertisingId

    @Test
    fun `advertisingId set change clear reflect on next event`() = runBlocking {
        val service = service(monitor = null)
        val first = "550e8400-e29b-41d4-a716-446655440000"
        val second = "6d92078a-8246-4ba4-ae5b-76104861e7dc"

        assertNull(service.track("a").context.device?.advertisingId)

        coEvery { identityManager.getAdvertisingId() } returns first
        assertEquals(first, service.track("b").context.device?.advertisingId)

        coEvery { identityManager.getAdvertisingId() } returns second
        assertEquals(second, service.track("c").context.device?.advertisingId)

        coEvery { identityManager.getAdvertisingId() } returns null
        assertNull(service.track("d").context.device?.advertisingId)
    }

    @Test
    fun `advertisingId overlay keeps static device fields`() = runBlocking {
        coEvery { identityManager.getAdvertisingId() } returns "550e8400-e29b-41d4-a716-446655440000"
        val device = service(monitor = null).track("a").context.device!!
        assertEquals("Google", device.manufacturer)
        assertEquals("Pixel 8", device.model)
        assertEquals("android", device.type)
    }
}
