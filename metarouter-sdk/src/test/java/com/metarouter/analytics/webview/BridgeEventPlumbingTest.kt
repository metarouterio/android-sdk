package com.metarouter.analytics.webview

import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
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

    private val enrichment = EventEnrichmentService(
        identityManager = identityManager,
        contextProvider = contextProvider,
        writeKey = "test-key"
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
}
