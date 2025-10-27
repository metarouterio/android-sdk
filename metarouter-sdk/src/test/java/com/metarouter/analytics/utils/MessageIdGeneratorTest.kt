package com.metarouter.analytics.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MessageIdGeneratorTest {

    @Test
    fun `generate creates valid message ID`() {
        val messageId = MessageIdGenerator.generate()

        assertNotNull(messageId)
        assertTrue(messageId.isNotEmpty())
        assertTrue(MessageIdGenerator.isValid(messageId))
    }

    @Test
    fun `generate creates unique message IDs`() {
        val id1 = MessageIdGenerator.generate()
        val id2 = MessageIdGenerator.generate()

        assertNotEquals(id1, id2)
    }

    @Test
    fun `generate with timestamp creates message ID with that timestamp`() {
        val timestamp = 1697545200000L
        val messageId = MessageIdGenerator.generate(timestamp)

        assertTrue(messageId.startsWith("1697545200000-"))
        assertEquals(timestamp, MessageIdGenerator.extractTimestamp(messageId))
    }

    @Test
    fun `message ID format is timestamp-uuid`() {
        val timestamp = 1234567890000L
        val messageId = MessageIdGenerator.generate(timestamp)

        val parts = messageId.split("-")
        // Should have 6 parts: timestamp + 5 UUID parts
        assertEquals(6, parts.size)

        // First part is timestamp
        assertEquals("1234567890000", parts[0])

        // Remaining parts form a valid UUID
        val uuidPart = parts.drop(1).joinToString("-")
        assertNotNull(UUID.fromString(uuidPart))
    }

    @Test
    fun `extractTimestamp returns correct timestamp`() {
        val timestamp = 9876543210000L
        val messageId = MessageIdGenerator.generate(timestamp)

        assertEquals(timestamp, MessageIdGenerator.extractTimestamp(messageId))
    }

    @Test
    fun `extractTimestamp returns null for invalid format`() {
        assertNull(MessageIdGenerator.extractTimestamp("invalid"))
        assertNull(MessageIdGenerator.extractTimestamp(""))
        assertNull(MessageIdGenerator.extractTimestamp("not-a-timestamp-abc"))
    }

    @Test
    fun `isValid returns true for valid message ID`() {
        val messageId = MessageIdGenerator.generate()
        assertTrue(MessageIdGenerator.isValid(messageId))
    }

    @Test
    fun `isValid returns false for empty string`() {
        assertFalse(MessageIdGenerator.isValid(""))
    }

    @Test
    fun `isValid returns false for invalid format`() {
        assertFalse(MessageIdGenerator.isValid("invalid"))
        assertFalse(MessageIdGenerator.isValid("123-abc"))
        assertFalse(MessageIdGenerator.isValid("not-a-message-id"))
    }

    @Test
    fun `isValid returns false for negative timestamp`() {
        val messageId = "-1-${UUID.randomUUID()}"
        assertFalse(MessageIdGenerator.isValid(messageId))
    }

    @Test
    fun `isValid returns false for zero timestamp`() {
        val messageId = "0-${UUID.randomUUID()}"
        assertFalse(MessageIdGenerator.isValid(messageId))
    }

    @Test
    fun `isValid returns false for non-numeric timestamp`() {
        val messageId = "abc-${UUID.randomUUID()}"
        assertFalse(MessageIdGenerator.isValid(messageId))
    }

    @Test
    fun `isValid returns false for malformed UUID`() {
        assertFalse(MessageIdGenerator.isValid("1234567890-not-a-uuid"))
        assertFalse(MessageIdGenerator.isValid("1234567890-abc-def-ghi"))
    }

    @Test
    fun `message IDs with different timestamps are unique`() {
        val id1 = MessageIdGenerator.generate(1000L)
        val id2 = MessageIdGenerator.generate(2000L)

        assertNotEquals(id1, id2)

        val ts1 = MessageIdGenerator.extractTimestamp(id1)
        val ts2 = MessageIdGenerator.extractTimestamp(id2)

        assertEquals(1000L, ts1)
        assertEquals(2000L, ts2)
    }

    @Test
    fun `message IDs with same timestamp have different UUIDs`() {
        val timestamp = 1697545200000L
        val id1 = MessageIdGenerator.generate(timestamp)
        val id2 = MessageIdGenerator.generate(timestamp)

        assertNotEquals(id1, id2)
        assertEquals(timestamp, MessageIdGenerator.extractTimestamp(id1))
        assertEquals(timestamp, MessageIdGenerator.extractTimestamp(id2))
    }
}
