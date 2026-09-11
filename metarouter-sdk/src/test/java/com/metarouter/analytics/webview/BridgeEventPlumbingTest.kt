package com.metarouter.analytics.webview

import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EventContext
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LibraryContext
import com.metarouter.analytics.types.PageContext
import com.metarouter.analytics.utils.MessageIdGenerator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the plumbing that carries a bridge envelope's page facts onto the outbound
 * payload: BaseEvent.page → enrichment → context.page.
 */
class BridgeEventPlumbingTest {

    private val identityManager = mockk<IdentityManager> {
        coEvery { getAnonymousId() } returns "anon-1"
        every { getUserId() } returns null
        every { getGroupId() } returns null
        coEvery { getAdvertisingId() } returns null
    }

    private val contextProvider = mockk<DeviceContextProvider> {
        every { getContext() } returns EventContext(
            library = LibraryContext(name = "metarouter-android-sdk", version = "test")
        )
    }

    // Real manager over a mocked empty store — enrichment mints the session
    // it stamps, so the plumbing test needs real mint logic, not a stub.
    private val sessionStorage = mockk<SessionStorage>(relaxUnitFun = true) {
        every { getSessionId() } returns null
        every { getSessionCount() } returns null
        every { getLastActivityMs() } returns null
    }

    private val enrichment = EventEnrichmentService(
        identityManager = identityManager,
        contextProvider = contextProvider,
        writeKey = "test-key",
        // Explicit clocks: this test runs on plain JUnit, where the default
        // SystemClock.elapsedRealtime is an unmocked android.os stub.
        sessionManager = SessionManager(
            storage = sessionStorage,
            wallClockMillis = { 1_757_400_000_000L },
            monotonicClockMillis = { 50_000L }
        )
    )

    @Test
    fun `bridge event page facts land on context page`() = runTest {
        val enriched = enrichment.enrichEvent(
            BaseEvent(
                type = EventType.PAGE,
                event = "page_view",
                page = PageContext(
                    url = "https://www.metarouter.com/booking",
                    title = "Book",
                    referrer = "https://www.metarouter.com/"
                )
            )
        )

        assertEquals("https://www.metarouter.com/booking", enriched.context.page?.url)
        assertEquals("Book", enriched.context.page?.title)
        assertEquals("https://www.metarouter.com/", enriched.context.page?.referrer)
    }

    @Test
    fun `native events carry no page context`() = runTest {
        val enriched = enrichment.enrichEvent(
            BaseEvent(type = EventType.TRACK, event = "native_action")
        )

        assertNull(enriched.context.page)
    }

    @Test
    fun `identity and messageId come from native enrichment not the envelope`() = runTest {
        val enriched = enrichment.enrichEvent(
            BaseEvent(
                type = EventType.TRACK,
                event = "product_viewed",
                page = PageContext(url = "https://www.metarouter.com/search")
            )
        )

        assertEquals("anon-1", enriched.anonymousId)
        // The envelope's messageId exists for bridge dedup only; the outbound event
        // gets a native ID like every other event.
        assertTrue(MessageIdGenerator.isValid(enriched.messageId))
    }

    /**
     * Bridge events flow through the same enrichment funnel as native events,
     * so they carry the session stamp — and stamping must not disturb the page
     * block the bridge exists to deliver.
     */
    @Test
    fun `bridge event carries session stamp and page untouched`() = runTest {
        val enriched = enrichment.enrichEvent(
            BaseEvent(
                type = EventType.TRACK,
                event = "Web Click",
                page = PageContext(url = "https://shop.example.com/cart", title = "Cart")
            )
        )

        assertEquals("https://shop.example.com/cart", enriched.context.page?.url)
        val session = enriched.context.providers?.get("metarouter")
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive("1757400000000"),
            session?.get("sessionID")
        )
    }
}
