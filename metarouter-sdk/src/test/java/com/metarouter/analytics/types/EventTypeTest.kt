package com.metarouter.analytics.types

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EventTypeTest {

    private val json = Json

    @Test
    fun `toString returns lowercase string`() {
        assertEquals("track", EventType.TRACK.toString())
        assertEquals("identify", EventType.IDENTIFY.toString())
        assertEquals("group", EventType.GROUP.toString())
        assertEquals("screen", EventType.SCREEN.toString())
        assertEquals("page", EventType.PAGE.toString())
        assertEquals("alias", EventType.ALIAS.toString())
    }

    @Test
    fun `serialization uses lowercase names`() {
        assertEquals("\"track\"", json.encodeToString(EventType.TRACK))
        assertEquals("\"identify\"", json.encodeToString(EventType.IDENTIFY))
        assertEquals("\"group\"", json.encodeToString(EventType.GROUP))
        assertEquals("\"screen\"", json.encodeToString(EventType.SCREEN))
        assertEquals("\"page\"", json.encodeToString(EventType.PAGE))
        assertEquals("\"alias\"", json.encodeToString(EventType.ALIAS))
    }

    @Test
    fun `deserialization from lowercase names`() {
        assertEquals(EventType.TRACK, json.decodeFromString<EventType>("\"track\""))
        assertEquals(EventType.IDENTIFY, json.decodeFromString<EventType>("\"identify\""))
        assertEquals(EventType.GROUP, json.decodeFromString<EventType>("\"group\""))
        assertEquals(EventType.SCREEN, json.decodeFromString<EventType>("\"screen\""))
        assertEquals(EventType.PAGE, json.decodeFromString<EventType>("\"page\""))
        assertEquals(EventType.ALIAS, json.decodeFromString<EventType>("\"alias\""))
    }

    @Test
    fun `all event types are present`() {
        val allTypes = EventType.values()
        assertEquals(6, allTypes.size)
        assertTrue(allTypes.contains(EventType.TRACK))
        assertTrue(allTypes.contains(EventType.IDENTIFY))
        assertTrue(allTypes.contains(EventType.GROUP))
        assertTrue(allTypes.contains(EventType.SCREEN))
        assertTrue(allTypes.contains(EventType.PAGE))
        assertTrue(allTypes.contains(EventType.ALIAS))
    }
}
