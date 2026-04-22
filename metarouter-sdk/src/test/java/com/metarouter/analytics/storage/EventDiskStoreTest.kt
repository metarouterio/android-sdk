package com.metarouter.analytics.storage

import com.metarouter.analytics.types.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class EventDiskStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: EventDiskStore

    @Before
    fun setup() {
        tempDir = createTempDirectory("metarouter-test").toFile()
        store = EventDiskStore(tempDir)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `write and read roundtrip preserves events`() {
        val events = listOf(createTestEvent("msg-1"), createTestEvent("msg-2"))
        val snapshot = QueueSnapshot(version = 1, events = events)

        store.write(snapshot)
        val loaded = store.read()

        assertNotNull(loaded)
        assertEquals(2, loaded!!.events.size)
        assertEquals("msg-1", loaded.events[0].messageId)
        assertEquals("msg-2", loaded.events[1].messageId)
        assertEquals(1, loaded.version)
    }

    @Test
    fun `read returns null when no file exists`() {
        val loaded = store.read()
        assertNull(loaded)
    }

    @Test
    fun `delete removes the snapshot file`() {
        val snapshot = QueueSnapshot(version = 1, events = listOf(createTestEvent("msg-1")))
        store.write(snapshot)

        store.delete()

        assertNull(store.read())
    }

    @Test
    fun `write creates parent directories if needed`() {
        val nestedDir = File(tempDir, "deeply/nested/path")
        val nestedStore = EventDiskStore(nestedDir)
        val snapshot = QueueSnapshot(version = 1, events = listOf(createTestEvent("msg-1")))

        nestedStore.write(snapshot)
        val loaded = nestedStore.read()

        assertNotNull(loaded)
        assertEquals(1, loaded!!.events.size)
    }

    @Test(expected = Exception::class)
    fun `read throws on corrupted file and still deletes it`() {
        val dir = File(tempDir, "metarouter/disk-queue")
        dir.mkdirs()
        val corruptFile = File(dir, "queue.v1.json")
        corruptFile.writeText("not valid json {{{")

        try {
            store.read()
        } finally {
            assertFalse("corrupt file must be deleted even though read threw", corruptFile.exists())
        }
    }

    @Test(expected = Exception::class)
    fun `read throws on unrecognized schema version and still deletes file`() {
        val dir = File(tempDir, "metarouter/disk-queue")
        dir.mkdirs()
        val versionFile = File(dir, "queue.v1.json")
        versionFile.writeText("""{"version":99,"events":[]}""")

        try {
            store.read()
        } finally {
            assertFalse("version-mismatched file must be deleted even though read threw", versionFile.exists())
        }
    }

    @Test
    fun `write overwrites existing file`() {
        val first = QueueSnapshot(version = 1, events = listOf(createTestEvent("msg-1")))
        store.write(first)

        val second = QueueSnapshot(version = 1, events = listOf(createTestEvent("msg-2"), createTestEvent("msg-3")))
        store.write(second)

        val loaded = store.read()
        assertNotNull(loaded)
        assertEquals(2, loaded!!.events.size)
        assertEquals("msg-2", loaded.events[0].messageId)
    }

    @Test
    fun `write uses atomic rename - no tmp file remains`() {
        val snapshot = QueueSnapshot(version = 1, events = listOf(createTestEvent("msg-1")))
        store.write(snapshot)

        val dir = File(tempDir, "metarouter/disk-queue")
        val finalFile = File(dir, "queue.v1.json")
        val tmpFile = File(dir, "queue.v1.json.tmp")

        assertTrue(finalFile.exists())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun `read skips individual corrupt events and keeps valid ones`() {
        // Build a JSON snapshot with one valid event and one corrupt event manually
        val validEvent = createTestEvent("msg-good")
        val validJson = Json { encodeDefaults = true }.encodeToString(validEvent)

        val dir = File(tempDir, "metarouter/disk-queue")
        dir.mkdirs()
        val snapshotFile = File(dir, "queue.v1.json")
        // Write a snapshot where one event is valid JSON but missing required fields
        snapshotFile.writeText("""{"version":1,"events":[$validJson,{"type":"track","broken":true}]}""")

        val loaded = store.read()

        assertNotNull(loaded)
        assertEquals(1, loaded!!.events.size)
        assertEquals("msg-good", loaded.events[0].messageId)
    }

    @Test
    fun `read skips all corrupt events and returns empty list`() {
        val dir = File(tempDir, "metarouter/disk-queue")
        dir.mkdirs()
        val snapshotFile = File(dir, "queue.v1.json")
        snapshotFile.writeText("""{"version":1,"events":[{"broken":true},{"also":"broken"}]}""")

        val loaded = store.read()

        assertNotNull(loaded)
        assertEquals(0, loaded!!.events.size)
    }

    @Test
    fun `delete on nonexistent file does not throw`() {
        // Should not throw
        store.delete()
    }

    @Test(expected = Exception::class)
    fun `read throws when version field is missing and still deletes file`() {
        val dir = File(tempDir, "metarouter/disk-queue")
        dir.mkdirs()
        val snapshotFile = File(dir, "queue.v1.json")
        snapshotFile.writeText("""{"events":[]}""")

        try {
            store.read()
        } finally {
            assertFalse("version-less file must be deleted even though read threw", snapshotFile.exists())
        }
    }

    @Test
    fun `read returns null only when file is absent`() {
        // File absent → null (no log, no throw). Contract pinned so callers can
        // distinguish "no prior snapshot" from "real I/O failure".
        assertNull(store.read())
    }

    @Test(expected = Exception::class)
    fun `write throws on real I-O failure`() {
        // Poison the snapshot directory path with a regular file so mkdirs() and
        // the subsequent tmp-file write both fail at the filesystem level.
        val parent = File(tempDir, "metarouter")
        parent.mkdirs()
        val blocker = File(parent, "disk-queue")
        blocker.writeText("not a directory")

        val snapshot = QueueSnapshot(version = 1, events = listOf(createTestEvent("msg-1")))
        store.write(snapshot)
    }

    @Test(expected = Exception::class)
    fun `read throws on real I-O failure`() {
        // Place a directory where the snapshot file should be. snapshotFile.exists()
        // returns true, but readText() on a directory throws at the JVM layer.
        val snapshotDir = File(tempDir, "metarouter/disk-queue")
        snapshotDir.mkdirs()
        val dirWhereFileShouldBe = File(snapshotDir, "queue.v1.json")
        dirWhereFileShouldBe.mkdirs()

        store.read()
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
