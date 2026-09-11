package com.metarouter.analytics.enrichment

import android.content.Context
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.DeviceContext
import com.metarouter.analytics.types.EventContext
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LibraryContext
import com.metarouter.analytics.utils.MessageIdGenerator
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
class EventEnrichmentServiceTest {

    private lateinit var context: Context
    private lateinit var identityManager: IdentityManager
    private lateinit var contextProvider: DeviceContextProvider
    private lateinit var sessionStorage: SessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var enrichmentService: EventEnrichmentService

    private val testWriteKey = "test-write-key-12345"
    private val testAnonymousId = "test-anonymous-id"
    private val testUserId = "test-user-id"
    private val testGroupId = "test-group-id"
    private var wallNow = 1_757_400_000_000L
    private var monoNow = 50_000L

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        contextProvider = mockk(relaxed = true)

        // Mock identity manager responses
        coEvery { identityManager.getAnonymousId() } returns testAnonymousId
        coEvery { identityManager.getUserId() } returns testUserId
        coEvery { identityManager.getGroupId() } returns testGroupId

        // Mock context provider
        every { contextProvider.getContext() } returns mockk(relaxed = true)

        // Real manager over a mocked empty store: enrichment now drives the
        // session lifecycle, so the stamp must come from real mint logic.
        sessionStorage = mockk(relaxUnitFun = true) {
            every { getSessionId() } returns null
            every { getSessionCount() } returns null
            every { getLastActivityMs() } returns null
        }
        wallNow = 1_757_400_000_000L
        monoNow = 50_000L
        sessionManager = SessionManager(
            storage = sessionStorage,
            wallClockMillis = { wallNow },
            monotonicClockMillis = { monoNow }
        )

        enrichmentService = EventEnrichmentService(
            identityManager = identityManager,
            contextProvider = contextProvider,
            writeKey = testWriteKey,
            sessionManager = sessionManager
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enrichEvent adds anonymousId from identity manager`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(testAnonymousId, enriched.anonymousId)
        coVerify { identityManager.getAnonymousId() }
    }

    @Test
    fun `enrichEvent adds userId from identity manager`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(testUserId, enriched.userId)
        coVerify { identityManager.getUserId() }
    }

    @Test
    fun `enrichEvent adds groupId from identity manager`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(testGroupId, enriched.groupId)
        coVerify { identityManager.getGroupId() }
    }

    @Test
    fun `enrichEvent generates messageId with correct format`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        // MessageId format: {timestamp}-{uuid}
        assertTrue(enriched.messageId.isNotEmpty())
        assertTrue(MessageIdGenerator.isValid(enriched.messageId))

        // Extract timestamp
        val timestamp = MessageIdGenerator.extractTimestamp(enriched.messageId)
        assertNotNull(timestamp)
        assertTrue(timestamp!! > 0)
    }

    @Test
    fun `enrichEvent adds writeKey`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(testWriteKey, enriched.writeKey)
    }

    @Test
    fun `enrichEvent uses provided timestamp`() = runBlocking {
        val customTimestamp = "2024-01-15T10:30:00.000Z"
        val baseEvent = BaseEvent(
            type = EventType.TRACK,
            event = "Test Event",
            timestamp = customTimestamp
        )

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(customTimestamp, enriched.timestamp)
    }

    @Test
    fun `enrichEvent generates timestamp when not provided`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        // Should be ISO 8601 format
        assertNotNull(enriched.timestamp)
        assertTrue(enriched.timestamp.isNotEmpty())

        // Validate format: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
        val iso8601Pattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        assertTrue(
            "Timestamp ${enriched.timestamp} does not match ISO 8601 format",
            iso8601Pattern.matches(enriched.timestamp)
        )

        // Parse and verify it's a valid date
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val parsed = format.parse(enriched.timestamp)
        assertNotNull(parsed)
    }

    @Test
    fun `enrichEvent preserves event type`() = runBlocking {
        EventType.values().forEach { eventType ->
            val baseEvent = BaseEvent(type = eventType, event = "Test")
            val enriched = enrichmentService.enrichEvent(baseEvent)
            assertEquals(eventType, enriched.type)
        }
    }

    @Test
    fun `enrichEvent preserves event name`() = runBlocking {
        val eventName = "Purchase Completed"
        val baseEvent = BaseEvent(type = EventType.TRACK, event = eventName)

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(eventName, enriched.event)
    }

    @Test
    fun `enrichEvent preserves properties`() = runBlocking {
        val properties = mapOf(
            "price" to JsonPrimitive(29.99),
            "currency" to JsonPrimitive("USD"),
            "quantity" to JsonPrimitive(2)
        )
        val baseEvent = BaseEvent(
            type = EventType.TRACK,
            event = "Purchase",
            properties = properties
        )

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(properties, enriched.properties)
    }

    @Test
    fun `enrichEvent preserves traits`() = runBlocking {
        val traits = mapOf(
            "email" to JsonPrimitive("user@example.com"),
            "name" to JsonPrimitive("John Doe"),
            "age" to JsonPrimitive(30)
        )
        val baseEvent = BaseEvent(
            type = EventType.IDENTIFY,
            traits = traits
        )

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(traits, enriched.traits)
    }

    @Test
    fun `enrichEvent sets sentAt to null`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        // sentAt is added at drain time, not during enrichment
        assertNull(enriched.sentAt)
    }

    @Test
    fun `enrichEvent handles null userId`() = runBlocking {
        coEvery { identityManager.getUserId() } returns null

        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")
        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertNull(enriched.userId)
    }

    @Test
    fun `enrichEvent handles null groupId`() = runBlocking {
        coEvery { identityManager.getGroupId() } returns null

        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")
        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertNull(enriched.groupId)
    }

    @Test
    fun `enrichEvent includes context from provider`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertNotNull(enriched.context)
        verify { contextProvider.getContext() }
    }

    @Test
    fun `enrichEvent generates unique messageIds for multiple events`() = runBlocking {
        val messageIds = mutableSetOf<String>()

        repeat(100) {
            val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event $it")
            val enriched = enrichmentService.enrichEvent(baseEvent)
            messageIds.add(enriched.messageId)
        }

        // All messageIds should be unique
        assertEquals(100, messageIds.size)
    }

    @Test
    fun `enrichEvent works with all event types`() = runBlocking {
        val eventTypes = listOf(
            EventType.TRACK to "Button Clicked",
            EventType.IDENTIFY to null,
            EventType.GROUP to null,
            EventType.SCREEN to "Home Screen",
            EventType.PAGE to "Landing Page",
            EventType.ALIAS to null
        )

        eventTypes.forEach { (type, eventName) ->
            val baseEvent = BaseEvent(type = type, event = eventName)
            val enriched = enrichmentService.enrichEvent(baseEvent)

            assertEquals(type, enriched.type)
            assertEquals(eventName, enriched.event)
            assertEquals(testAnonymousId, enriched.anonymousId)
            assertEquals(testWriteKey, enriched.writeKey)
            assertNotNull(enriched.messageId)
            assertNotNull(enriched.timestamp)
            assertNotNull(enriched.context)
        }
    }

    @Test
    fun `enrichEvent handles complex nested properties`() = runBlocking {
        val properties = mapOf(
            "product" to JsonObject(
                mapOf(
                    "id" to JsonPrimitive("prod-123"),
                    "name" to JsonPrimitive("Widget"),
                    "price" to JsonPrimitive(29.99),
                    "tags" to JsonArray(
                        listOf(
                            JsonPrimitive("electronics"),
                            JsonPrimitive("gadgets")
                        )
                    )
                )
            ),
            "metadata" to JsonObject(
                mapOf(
                    "source" to JsonPrimitive("mobile"),
                    "version" to JsonPrimitive(2)
                )
            )
        )

        val baseEvent = BaseEvent(
            type = EventType.TRACK,
            event = "Product Viewed",
            properties = properties
        )

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertEquals(properties, enriched.properties)
    }

    @Test
    fun `enrichEvent includes advertisingId in device context when set`() = runBlocking {
        val testAdvertisingId = "test-gaid-12345"
        coEvery { identityManager.getAdvertisingId() } returns testAdvertisingId

        val deviceContext = DeviceContext(
            manufacturer = "Google",
            model = "Pixel 6",
            name = "pixel6",
            type = "android"
        )
        val eventContext = EventContext(
            device = deviceContext,
            library = LibraryContext(name = "metarouter-android", version = "1.2.0")
        )
        every { contextProvider.getContext() } returns eventContext

        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")
        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertNotNull(enriched.context.device)
        assertEquals(testAdvertisingId, enriched.context.device?.advertisingId)
        coVerify { identityManager.getAdvertisingId() }
    }

    @Test
    fun `enrichEvent excludes advertisingId when not set`() = runBlocking {
        coEvery { identityManager.getAdvertisingId() } returns null

        val deviceContext = DeviceContext(
            manufacturer = "Google",
            model = "Pixel 6",
            name = "pixel6",
            type = "android"
        )
        val eventContext = EventContext(
            device = deviceContext,
            library = LibraryContext(name = "metarouter-android", version = "1.2.0")
        )
        every { contextProvider.getContext() } returns eventContext

        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")
        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertNotNull(enriched.context.device)
        assertNull(enriched.context.device?.advertisingId)
    }

    @Test
    fun `enrichEvent handles null device context gracefully when advertisingId set`() = runBlocking {
        val testAdvertisingId = "test-gaid-12345"
        coEvery { identityManager.getAdvertisingId() } returns testAdvertisingId

        val eventContext = EventContext(
            device = null,
            library = LibraryContext(name = "metarouter-android", version = "1.2.0")
        )
        every { contextProvider.getContext() } returns eventContext

        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")
        val enriched = enrichmentService.enrichEvent(baseEvent)

        // Should not crash, device remains null
        assertNull(enriched.context.device)
    }

    // ===== Session stamping =====

    private fun sessionOf(enriched: com.metarouter.analytics.types.EnrichedEventPayload): JsonObject? =
        enriched.context.providers?.get("metarouter")

    /** The stamp lands via `copy()`, which a relaxed context mock cannot honor. */
    private fun useRealContext() {
        every { contextProvider.getContext() } returns EventContext(
            library = LibraryContext(name = "metarouter-android-sdk", version = "test")
        )
    }

    /**
     * Web-parity path pin: `context.providers.metarouter.{sessionID, sessionCount}`
     * is what pipeline mappings read on every platform — renaming any part forks them.
     */
    @Test
    fun `enriched event carries session stamp at providers metarouter`() = runBlocking {
        useRealContext()
        val enriched = enrichmentService.enrichEvent(
            BaseEvent(type = EventType.TRACK, event = "Order Completed")
        )

        val session = sessionOf(enriched)
        assertEquals(JsonPrimitive("1757400000000"), session?.get("sessionID"))
        assertEquals(JsonPrimitive(1), session?.get("sessionCount"))
    }

    @Test
    fun `events within timeout share one session`() = runBlocking {
        useRealContext()
        val first = enrichmentService.enrichEvent(BaseEvent(type = EventType.TRACK, event = "A"))
        wallNow += 20 * 60_000L
        monoNow += 20 * 60_000L
        val second = enrichmentService.enrichEvent(BaseEvent(type = EventType.SCREEN, event = "B"))

        assertEquals(sessionOf(first)?.get("sessionID"), sessionOf(second)?.get("sessionID"))
        assertEquals(JsonPrimitive(1), sessionOf(second)?.get("sessionCount"))
    }

    @Test
    fun `inactivity gap rolls session mid-stream`() = runBlocking {
        useRealContext()
        val before = enrichmentService.enrichEvent(BaseEvent(type = EventType.TRACK, event = "A"))
        wallNow += 31 * 60_000L
        monoNow += 31 * 60_000L
        val after = enrichmentService.enrichEvent(BaseEvent(type = EventType.TRACK, event = "B"))

        assertNotEquals(sessionOf(before)?.get("sessionID"), sessionOf(after)?.get("sessionID"))
        assertEquals(JsonPrimitive(wallNow.toString()), sessionOf(after)?.get("sessionID"))
        assertEquals(JsonPrimitive(2), sessionOf(after)?.get("sessionCount"))
    }

    /**
     * Events persisted to disk by a build that predates `providers` must still
     * decode — a required key here would silently discard a customer's entire
     * pre-upgrade offline queue on their first launch after updating.
     */
    @Test
    fun `payload without providers key still decodes`() {
        val legacyJson = """
            {
              "type": "track",
              "event": "Legacy Event",
              "anonymousId": "anon-legacy",
              "timestamp": "2026-01-01T00:00:00.000Z",
              "context": {"library": {"name": "metarouter-android-sdk", "version": "1.0.0"}},
              "messageId": "m",
              "writeKey": "k"
            }
        """.trimIndent()

        val payload = kotlinx.serialization.json.Json.decodeFromString(
            com.metarouter.analytics.types.EnrichedEventPayload.serializer(), legacyJson
        )
        assertEquals("Legacy Event", payload.event)
        assertNull(payload.context.providers)
    }
}
