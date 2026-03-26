package com.metarouter.analytics.storage

import com.metarouter.analytics.types.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueSnapshotTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `snapshot serializes and deserializes with events`() {
        val event = createTestEvent("msg-1")
        val snapshot = QueueSnapshot(
            version = 1,
            events = listOf(event)
        )

        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<QueueSnapshot>(encoded)

        assertEquals(1, decoded.version)
        assertEquals(1, decoded.events.size)
        assertEquals("msg-1", decoded.events[0].messageId)
    }

    @Test
    fun `snapshot serializes with empty events list`() {
        val snapshot = QueueSnapshot(
            version = 1,
            events = emptyList()
        )

        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<QueueSnapshot>(encoded)

        assertEquals(0, decoded.events.size)
    }

    @Test
    fun `snapshot version defaults to 1`() {
        val snapshot = QueueSnapshot(events = emptyList())
        assertEquals(1, snapshot.version)
    }

    @Test
    fun `snapshot with unknown version deserializes without error`() {
        val futureJson = """{"version":99,"events":[]}"""
        val decoded = json.decodeFromString<QueueSnapshot>(futureJson)
        assertEquals(99, decoded.version)
    }

    @Test
    fun `snapshot preserves all event fields through roundtrip`() {
        val event = createTestEvent("msg-full")
        val snapshot = QueueSnapshot(events = listOf(event))

        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<QueueSnapshot>(encoded)
        val restored = decoded.events[0]

        assertEquals(EventType.TRACK, restored.type)
        assertEquals("Test Event", restored.event)
        assertEquals("test-user", restored.userId)
        assertEquals("test-anon", restored.anonymousId)
        assertEquals("2026-01-01T00:00:00.000Z", restored.timestamp)
        assertEquals("test-key", restored.writeKey)
        assertEquals("msg-full", restored.messageId)
        assertEquals("metarouter-android", restored.context.library.name)
    }

    private fun createTestEvent(messageId: String): EnrichedEventPayload {
        return EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Test Event",
            userId = "test-user",
            anonymousId = "test-anon",
            groupId = null,
            traits = null,
            properties = null,
            timestamp = "2026-01-01T00:00:00.000Z",
            context = EventContext(
                library = LibraryContext(name = "metarouter-android", version = "1.0.0")
            ),
            messageId = messageId,
            writeKey = "test-key",
            sentAt = null
        )
    }
}
