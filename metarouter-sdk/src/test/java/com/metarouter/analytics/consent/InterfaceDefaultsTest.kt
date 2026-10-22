package com.metarouter.analytics.consent

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.AnalyticsInterface
import com.metarouter.analytics.AnalyticsProxy
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.InterfaceDefaults
import com.metarouter.analytics.MetaRouterAnalyticsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A host class implementing [AnalyticsInterface] that implements none of the members
 * added for the WebView bridge or consent. It compiling at all is the main assertion:
 * without the interface defaults, every minor release that grows the interface breaks
 * hosts' builds.
 */
private class LegacyHostFake : AnalyticsInterface {
    override fun track(event: String, properties: Map<String, Any?>?) {}
    override fun identify(userId: String, traits: Map<String, Any?>?) {}
    override fun group(groupId: String, traits: Map<String, Any?>?) {}
    override fun screen(name: String, properties: Map<String, Any?>?) {}
    override fun page(name: String, properties: Map<String, Any?>?) {}
    override fun alias(newUserId: String) {}
    override fun setAdvertisingId(advertisingId: String) {}
    override fun clearAdvertisingId() {}
    override suspend fun flush() {}
    override suspend fun reset() {}
    override suspend fun getAnonymousId(): String = ""
    override suspend fun getSessionId(): String? = null
    override fun enableDebugLogging() {}
    override suspend fun getDebugInfo(): Map<String, Any?> = emptyMap()
    override fun setTracing(enabled: Boolean) {}
    override fun recordOpenedUrl(uri: Uri, sourceApplication: String?) {}
}

@RunWith(RobolectricTestRunner::class)
class InterfaceDefaultsTest {

    private lateinit var storage: ConsentStorage

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(context).apply { clear() }
        InterfaceDefaults.reset()
    }

    @After
    fun tearDown() {
        storage.clear()
        InterfaceDefaults.reset()
        unmockkAll()
    }

    @Test
    fun `defaults are safe no-ops`() {
        val host: AnalyticsInterface = LegacyHostFake()

        host.registerConsentCategories(listOf(ConsentCategory("c0001")))
        host.clearConsent()
        assertFalse("nothing was applied", host.setConsent(Consent(explicit = true, optOut = mapOf("c0001" to true))))
        val subscription = host.subscribeToConsent { }
        subscription.close()
        assertTrue(subscription.isClosed)
        host.attachWebView(WebView(ApplicationProvider.getApplicationContext()), listOf("https://example.com"))

        assertEquals(Consent(explicit = false, optOut = emptyMap()), host.getConsent())
    }

    /** Every defaulted member, called through the interface — how a host holding it dispatches. */
    private fun driveEveryDefaultedMember(target: AnalyticsInterface) {
        target.attachWebView(WebView(ApplicationProvider.getApplicationContext()), listOf("https://example.com"))
        target.registerConsentCategories(listOf(ConsentCategory("c0001")))
        target.setConsent(Consent(explicit = true, optOut = mapOf("c0001" to true)))
        target.getConsent()
        target.subscribeToConsent { }.close()
        target.clearConsent()
    }

    /**
     * The defaults removed the compiler's override check, so this is what catches an SDK
     * method whose signature drifted off the member: the call would land on the
     * warn-and-ignore default instead — a consent withdrawal silently dropped.
     */
    @Test
    fun `SDK classes never fall through to a default`() = runBlocking {
        val manager = ConsentManager(storage, dispatch = { })
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val client = MetaRouterAnalyticsClient.initialize(
            context,
            InitOptions(writeKey = "wk", ingestionHost = "https://events.example.com", trackLifecycleEvents = false),
            consentManager = manager
        )
        val proxy = AnalyticsProxy(AnalyticsProxy.DEFAULT_MAX_PENDING_CALLS, manager)
        InterfaceDefaults.reset()

        driveEveryDefaultedMember(client)
        driveEveryDefaultedMember(proxy)

        assertEquals("an SDK class fell through to an interface default", emptySet<String>(), InterfaceDefaults.hits)
    }

    /** The probe itself must see a real fall-through, or the test above proves nothing. */
    @Test
    fun `probe records a host implementation falling through`() {
        driveEveryDefaultedMember(LegacyHostFake())

        assertEquals(6, InterfaceDefaults.hits.size)
    }

    /** A wrapper that forwards `track` but not `getConsent` must not log per event. */
    @Test
    fun `default warns once per class and method`() {
        ShadowLog.clear()
        val host: AnalyticsInterface = LegacyHostFake()

        repeat(5) { host.getConsent() }
        host.clearConsent()

        val messages = ShadowLog.getLogsForTag("MetaRouter").map { it.msg }
        assertEquals(1, messages.count { it.startsWith("getConsent is not implemented") })
        assertEquals(1, messages.count { it.startsWith("clearConsent is not implemented") })
        assertEquals(setOf("LegacyHostFake.getConsent", "LegacyHostFake.clearConsent"), InterfaceDefaults.hits)
    }
}
