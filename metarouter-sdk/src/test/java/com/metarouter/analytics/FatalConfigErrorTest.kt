package com.metarouter.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.network.FakeNetworkClient
import com.metarouter.analytics.network.FakeNetworkMonitor
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.storage.IdentityStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A 401/403/404 disables the client for the session, as the README says and iOS does. */
@RunWith(RobolectricTestRunner::class)
class FatalConfigErrorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val network = FakeNetworkClient().apply { nextResponse = NetworkResponse(401, emptyMap(), null) }
    private val clients = mutableListOf<MetaRouterAnalyticsClient>()

    @Before
    fun clearStorage() {
        IdentityStorage(context).clear()
        SessionStorage(context).clear()
    }

    @After
    fun resetClients() = runBlocking { clients.forEach { runCatching { it.reset() } } }

    private suspend fun makeClient(): MetaRouterAnalyticsClient =
        MetaRouterAnalyticsClient.initialize(
            context,
            InitOptions(
                writeKey = "wk",
                ingestionHost = "https://events.example.com",
                flushIntervalSeconds = 999,
                trackLifecycleEvents = false
            ),
            eventQueue = EventQueue(),
            networkClient = network,
            networkMonitor = FakeNetworkMonitor(),
            consentManager = ConsentManager(),
            providerContextManager = ProviderContextManager()
        ).also { clients += it }

    private suspend fun sendUntilRejected(client: MetaRouterAnalyticsClient) {
        client.track("first")
        val start = System.currentTimeMillis()
        while (network.requests.isEmpty() && System.currentTimeMillis() - start < 2000) {
            client.flush()
            delay(20)
        }
        delay(50)
    }

    @Test
    fun `a 401 disables the client and later events are dropped`() = runBlocking {
        val client = makeClient()
        sendUntilRejected(client)
        assertEquals("disabled", client.getDebugInfo()["lifecycle"])

        val requests = network.requests.size
        client.track("after the 401")
        client.flush()
        delay(100)

        assertEquals("no request after the client was disabled", requests, network.requests.size)
    }

    @Test
    fun `a disabled client still takes identity writes and reports identity`() = runBlocking {
        val client = makeClient()
        sendUntilRejected(client)

        client.setAdvertisingId("6D92078A-8246-4BA4-AE5B-76104861E7DC")
        assertEquals("6D92078A-8246-4BA4-AE5B-76104861E7DC",
            IdentityStorage(context).get(IdentityStorage.KEY_ADVERTISING_ID))
        client.clearAdvertisingId()
        assertNull(IdentityStorage(context).get(IdentityStorage.KEY_ADVERTISING_ID))
        assertTrue(client.getDebugInfo()["anonymousId"] != null)
    }

    @Test
    fun `a disabled client still answers getAnonymousId`() = runBlocking {
        val client = makeClient()
        sendUntilRejected(client)

        assertTrue(client.getAnonymousId().isNotEmpty())
    }

    @Test
    fun `a disabled client still resets and clears identity`() = runBlocking {
        val client = makeClient()
        client.identify("u1")
        sendUntilRejected(client)

        client.reset()

        assertEquals("idle", client.getDebugInfo()["lifecycle"])
        assertNull(IdentityStorage(context).get(IdentityStorage.KEY_USER_ID))
    }
}
