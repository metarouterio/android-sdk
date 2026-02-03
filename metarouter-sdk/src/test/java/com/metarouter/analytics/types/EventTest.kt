package com.metarouter.analytics.types

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for Event data models (BaseEvent, EnrichedEventPayload).
 */
class EventTest {

    @Test
    fun `BaseEvent contains only user-provided data`() {
        val event = BaseEvent(
            type = EventType.TRACK,
            event = "Purchase",
            traits = null,
            properties = mapOf(
                "item" to JsonPrimitive("Premium Plan"),
                "price" to JsonPrimitive(29.99)
            ),
            timestamp = null
        )

        assertEquals(EventType.TRACK, event.type)
        assertEquals("Purchase", event.event)
        assertNull(event.traits)
        assertNotNull(event.properties)
        assertEquals(2, event.properties?.size)
        assertNull(event.timestamp)
    }

    @Test
    fun `BaseEvent serializes to JSON correctly`() {
        val event = BaseEvent(
            type = EventType.IDENTIFY,
            event = null,
            traits = mapOf(
                "name" to JsonPrimitive("Alice"),
                "age" to JsonPrimitive(30)
            ),
            properties = null,
            timestamp = "2024-01-15T10:30:00.000Z"
        )

        val json = Json.encodeToString(event)

        assertTrue(json.contains("\"type\":\"identify\""))
        assertTrue(json.contains("\"name\":\"Alice\""))
        assertTrue(json.contains("\"age\":30"))
        assertTrue(json.contains("\"timestamp\":\"2024-01-15T10:30:00.000Z\""))
    }

    @Test
    fun `EnrichedEventPayload contains all required fields`() {
        val event = EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Purchase",
            userId = "user-123",
            anonymousId = "anon-abc-def",
            groupId = null,
            traits = null,
            properties = mapOf("item" to JsonPrimitive("Plan")),
            timestamp = "2024-01-15T10:30:00.000Z",
            context = EventContext(
                app = AppContext(
                    name = "Test App",
                    version = "1.0.2",
                    build = "100",
                    namespace = "com.test.app"
                ),
                device = DeviceContext(
                    manufacturer = "Google",
                    model = "Pixel 7",
                    name = "Test Device",
                    type = "Android"
                ),
                library = LibraryContext(
                    name = "MetaRouter Android SDK",
                    version = "1.0.2"
                ),
                os = OSContext(
                    name = "Android",
                    version = "14"
                ),
                screen = ScreenContext(
                    width = 1080,
                    height = 2400,
                    density = 3.0
                ),
                network = NetworkContext(
                    wifi = true
                ),
                locale = "en-US",
                timezone = "America/New_York"
            ),
            messageId = "1234567890-uuid",
            writeKey = "test-write-key",
            sentAt = "2024-01-15T10:30:01.000Z"
        )

        assertEquals(EventType.TRACK, event.type)
        assertEquals("anon-abc-def", event.anonymousId)
        assertEquals("1234567890-uuid", event.messageId)
        assertEquals("test-write-key", event.writeKey)
        assertEquals("2024-01-15T10:30:01.000Z", event.sentAt)
        assertNotNull(event.context)
    }

    @Test
    fun `EnrichedEventPayload serializes to complete JSON`() {
        val event = EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Button Clicked",
            userId = null,
            anonymousId = "anon-123",
            groupId = null,
            traits = null,
            properties = mapOf("button" to JsonPrimitive("Sign Up")),
            timestamp = "2024-01-15T10:30:00.000Z",
            context = EventContext(
                app = AppContext(
                    name = "Test App",
                    version = "1.0.2",
                    build = "100",
                    namespace = "com.test.app"
                ),
                device = DeviceContext(
                    manufacturer = "Google",
                    model = "Pixel 7",
                    name = "Test Device",
                    type = "Android"
                ),
                library = LibraryContext(
                    name = "MetaRouter Android SDK",
                    version = "1.0.2"
                ),
                os = OSContext(
                    name = "Android",
                    version = "14"
                ),
                screen = ScreenContext(
                    width = 1080,
                    height = 2400,
                    density = 3.0
                ),
                network = NetworkContext(
                    wifi = true
                ),
                locale = "en-US",
                timezone = "America/New_York"
            ),
            messageId = "msg-123",
            writeKey = "key-123",
            sentAt = null
        )

        val json = Json.encodeToString(event)

        assertTrue(json.contains("\"type\":\"track\""))
        assertTrue(json.contains("\"event\":\"Button Clicked\""))
        assertTrue(json.contains("\"anonymousId\":\"anon-123\""))
        assertTrue(json.contains("\"messageId\":\"msg-123\""))
        assertTrue(json.contains("\"writeKey\":\"key-123\""))
        assertTrue(json.contains("\"context\""))
        assertTrue(json.contains("\"app\""))
        assertTrue(json.contains("\"device\""))
    }

    @Test
    fun `BaseEvent with minimal fields`() {
        val event = BaseEvent(
            type = EventType.TRACK,
            event = "App Opened",
            traits = null,
            properties = null,
            timestamp = null
        )

        assertEquals(EventType.TRACK, event.type)
        assertEquals("App Opened", event.event)
        assertNull(event.traits)
        assertNull(event.properties)
        assertNull(event.timestamp)
    }

    @Test
    fun `Event types have correct field combinations`() {
        // TRACK event
        val trackEvent = BaseEvent(
            type = EventType.TRACK,
            event = "Purchase",
            properties = mapOf("item" to JsonPrimitive("Plan")),
            traits = null,
            timestamp = null
        )
        assertEquals(EventType.TRACK, trackEvent.type)
        assertNotNull(trackEvent.event)
        assertNotNull(trackEvent.properties)
        assertNull(trackEvent.traits)

        // IDENTIFY event
        val identifyEvent = BaseEvent(
            type = EventType.IDENTIFY,
            event = null,
            properties = null,
            traits = mapOf("name" to JsonPrimitive("Alice")),
            timestamp = null
        )
        assertEquals(EventType.IDENTIFY, identifyEvent.type)
        assertNull(identifyEvent.event)
        assertNull(identifyEvent.properties)
        assertNotNull(identifyEvent.traits)

        // SCREEN event
        val screenEvent = BaseEvent(
            type = EventType.SCREEN,
            event = "Home Screen",
            properties = mapOf("referrer" to JsonPrimitive("notification")),
            traits = null,
            timestamp = null
        )
        assertEquals(EventType.SCREEN, screenEvent.type)
        assertNotNull(screenEvent.event)
        assertNotNull(screenEvent.properties)
    }
}
