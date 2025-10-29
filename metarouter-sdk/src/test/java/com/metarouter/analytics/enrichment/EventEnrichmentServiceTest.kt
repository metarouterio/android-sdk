package com.metarouter.analytics.enrichment

import android.content.Context
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.CodableValue
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.utils.MessageIdGenerator
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class EventEnrichmentServiceTest {

    private lateinit var context: Context
    private lateinit var identityManager: IdentityManager
    private lateinit var contextProvider: DeviceContextProvider
    private lateinit var enrichmentService: EventEnrichmentService

    private val testWriteKey = "test-write-key-12345"
    private val testAnonymousId = "test-anonymous-id"
    private val testUserId = "test-user-id"
    private val testGroupId = "test-group-id"
    private val testAdvertisingId = "test-advertising-id"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        contextProvider = mockk(relaxed = true)

        // Mock identity manager responses
        coEvery { identityManager.getAnonymousId() } returns testAnonymousId
        coEvery { identityManager.getUserId() } returns testUserId
        coEvery { identityManager.getGroupId() } returns testGroupId
        coEvery { identityManager.getAdvertisingId() } returns testAdvertisingId

        // Mock context provider
        every { contextProvider.getContext(any()) } returns mockk(relaxed = true)

        enrichmentService = EventEnrichmentService(
            identityManager = identityManager,
            contextProvider = contextProvider,
            writeKey = testWriteKey
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
    fun `enrichEvent adds advertising ID to context`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        enrichmentService.enrichEvent(baseEvent)

        coVerify { identityManager.getAdvertisingId() }
        verify { contextProvider.getContext(testAdvertisingId) }
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
            "price" to CodableValue.DoubleValue(29.99),
            "currency" to CodableValue.StringValue("USD"),
            "quantity" to CodableValue.IntValue(2)
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
            "email" to CodableValue.StringValue("user@example.com"),
            "name" to CodableValue.StringValue("John Doe"),
            "age" to CodableValue.IntValue(30)
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
    fun `enrichEvent handles null advertisingId`() = runBlocking {
        coEvery { identityManager.getAdvertisingId() } returns null

        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")
        enrichmentService.enrichEvent(baseEvent)

        // Should pass null to context provider
        verify { contextProvider.getContext(null) }
    }

    @Test
    fun `enrichEvent includes context from provider`() = runBlocking {
        val baseEvent = BaseEvent(type = EventType.TRACK, event = "Test Event")

        val enriched = enrichmentService.enrichEvent(baseEvent)

        assertNotNull(enriched.context)
        verify { contextProvider.getContext(testAdvertisingId) }
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
            "product" to CodableValue.ObjectValue(
                mapOf(
                    "id" to CodableValue.StringValue("prod-123"),
                    "name" to CodableValue.StringValue("Widget"),
                    "price" to CodableValue.DoubleValue(29.99),
                    "tags" to CodableValue.ArrayValue(
                        listOf(
                            CodableValue.StringValue("electronics"),
                            CodableValue.StringValue("gadgets")
                        )
                    )
                )
            ),
            "metadata" to CodableValue.ObjectValue(
                mapOf(
                    "source" to CodableValue.StringValue("mobile"),
                    "version" to CodableValue.IntValue(2)
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
}
