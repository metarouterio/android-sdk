package com.metarouter.analytics.storage

import android.content.Context
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Handles reading/writing queue snapshot files to disk.
 *
 * Storage path: <baseDir>/metarouter/disk-queue/queue.v1.json
 * Uses atomic write-then-rename to prevent partial writes on crash.
 *
 * Error handling:
 * - Corrupt/unparseable files: returns null, deletes file, logs warning
 * - Unrecognized schema version: returns null, deletes file, logs warning
 * - Individual event deserialization failure: skips that event, keeps valid events, logs warning
 * - Missing file: returns null (no log)
 *
 * Thread safety: callers must synchronize externally (PersistableEventQueue does this).
 */
class EventDiskStore(private val baseDir: File) {

    companion object {
        private const val DIRECTORY_NAME = "metarouter"
        private const val SUBDIRECTORY_NAME = "disk-queue"
        private const val SNAPSHOT_FILENAME = "queue.v1.json"
        private const val CURRENT_VERSION = 1

        /**
         * Production factory: uses Context.noBackupFilesDir as base directory.
         */
        fun create(context: Context): EventDiskStore = EventDiskStore(context.noBackupFilesDir)
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val snapshotDir: File
        get() = File(baseDir, "$DIRECTORY_NAME/$SUBDIRECTORY_NAME")

    private val snapshotFile: File
        get() = File(snapshotDir, SNAPSHOT_FILENAME)

    private val tmpFile: File
        get() = File(snapshotDir, "$SNAPSHOT_FILENAME.tmp")

    /**
     * Write a snapshot to disk. Uses atomic write-to-tmp-then-rename.
     * Overwrites any existing snapshot completely.
     */
    fun write(snapshot: QueueSnapshot) {
        try {
            snapshotDir.mkdirs()
            val data = json.encodeToString(QueueSnapshot.serializer(), snapshot)
            tmpFile.writeText(data, Charsets.UTF_8)
            if (!tmpFile.renameTo(snapshotFile)) {
                // renameTo can fail on some filesystems; fall back to direct write
                snapshotFile.writeText(data, Charsets.UTF_8)
                tmpFile.delete()
            }
            Logger.log("Queue snapshot written to disk (${snapshot.events.size} events)")
        } catch (e: Exception) {
            Logger.error("Failed to write queue snapshot to disk: ${e.message}")
            tmpFile.delete()
        }
    }

    /**
     * Read the snapshot from disk with resilient per-event deserialization.
     *
     * Returns null if: file missing, corrupted JSON structure, unrecognized version.
     * Individual events that fail deserialization are skipped (not fatal).
     * Deletes corrupt files and files with unrecognized versions.
     */
    fun read(): QueueSnapshot? {
        if (!snapshotFile.exists()) return null

        return try {
            val data = snapshotFile.readText(Charsets.UTF_8)

            // Parse as raw JSON first to extract version and handle events individually
            val jsonObject = json.parseToJsonElement(data).jsonObject

            val version = jsonObject["version"]?.jsonPrimitive?.int
                ?: run {
                    Logger.warn("Queue snapshot missing version field")
                    delete()
                    return null
                }

            if (version != CURRENT_VERSION) {
                Logger.warn("Skipping queue snapshot with unrecognized version: $version (expected $CURRENT_VERSION)")
                delete()
                return null
            }

            val eventsArray = jsonObject["events"]?.jsonArray
                ?: run {
                    Logger.warn("Queue snapshot missing events array")
                    delete()
                    return null
                }

            // Resilient per-event deserialization: skip individual corrupt events
            var skipped = 0
            val events = eventsArray.mapNotNull { element ->
                try {
                    json.decodeFromJsonElement(EnrichedEventPayload.serializer(), element)
                } catch (e: Exception) {
                    skipped++
                    Logger.warn("Skipped unreadable event during queue snapshot decode: ${e.message}")
                    null
                }
            }

            if (skipped > 0) {
                Logger.warn("Skipped $skipped unreadable event(s) during queue snapshot decode")
            }

            Logger.log("Queue snapshot loaded from disk (${events.size} events)")
            QueueSnapshot(version = version, events = events)
        } catch (e: Exception) {
            Logger.warn("Failed to read queue snapshot from disk (corrupted?): ${e.message}")
            delete()
            null
        }
    }

    /**
     * Delete the snapshot file from disk.
     */
    fun delete() {
        try {
            snapshotFile.delete()
            tmpFile.delete()
        } catch (e: Exception) {
            Logger.warn("Failed to delete queue snapshot: ${e.message}")
        }
    }

}
