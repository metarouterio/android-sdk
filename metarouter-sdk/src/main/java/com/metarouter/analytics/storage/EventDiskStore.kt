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
import java.io.IOException

/**
 * Handles reading/writing queue snapshot files to disk.
 *
 * Storage path: <baseDir>/metarouter/disk-queue/queue.v1.json
 * Uses atomic write-then-rename to prevent partial writes on crash.
 *
 * Error handling:
 * - Missing file on read: returns null (no log, no throw)
 * - Corrupt / unparseable snapshot, unrecognized version, missing version field:
 *   deletes the offending file, then throws IOException so callers can distinguish
 *   real failure from "nothing on disk"
 * - Individual event deserialization failure: skips that event, keeps valid events, logs warning
 * - write failures: throws IOException after cleaning up the tmp file
 * - delete on absent file: no-op; delete on real I/O failure: throws IOException
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
         * Falls back to Context.filesDir, then system temp directory.
         */
        fun create(context: Context): EventDiskStore {
            val baseDir = listOf(
                { context.noBackupFilesDir },
                { context.filesDir }
            ).firstNotNullOfOrNull { provider ->
                try {
                    val dir = provider() ?: return@firstNotNullOfOrNull null
                    // Validate the File has a usable internal path (constructing a child
                    // will NPE if the File's internal path field is null, e.g. from mocks)
                    File(dir, "test-path-validation")
                    dir
                } catch (_: Exception) {
                    null
                }
            } ?: File(System.getProperty("java.io.tmpdir", "/tmp"), "metarouter-fallback")
            return EventDiskStore(baseDir)
        }
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

    /** Cheap check — does a snapshot file exist on disk? */
    fun exists(): Boolean = snapshotFile.exists()

    /**
     * Write a snapshot to disk. Uses atomic write-to-tmp-then-rename.
     * Overwrites any existing snapshot completely.
     *
     * Throws IOException if the write is not durable. Callers are expected to
     * treat a throw as "batch still owned by the caller" so events can be retried.
     */
    @Throws(IOException::class)
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
            try { tmpFile.delete() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException("Failed to write queue snapshot", e)
        }
    }

    /**
     * Read the snapshot from disk with resilient per-event deserialization.
     *
     * Returns null ONLY when the snapshot file is absent — that is the "nothing
     * persisted" signal. All other failure modes (I/O error, corrupt JSON,
     * unrecognized version, missing version field) delete the offending file
     * and throw IOException, so callers can distinguish "no prior work" from
     * "real error worth surfacing."
     *
     * Individual events that fail deserialization are still tolerated (skipped,
     * not fatal) — a best-effort within an otherwise-valid envelope.
     */
    @Throws(IOException::class)
    fun read(): QueueSnapshot? {
        if (!snapshotFile.exists()) return null

        val data = try {
            snapshotFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.warn("Failed to read queue snapshot from disk: ${e.message}")
            throw if (e is IOException) e else IOException("Failed to read queue snapshot", e)
        }

        val parsed = try {
            // Parse as raw JSON first to extract version and handle events individually
            val jsonObject = json.parseToJsonElement(data).jsonObject

            val version = jsonObject["version"]?.jsonPrimitive?.int
                ?: throw IOException("Queue snapshot missing version field")

            if (version != CURRENT_VERSION) {
                throw IOException("Queue snapshot has unrecognized version $version (expected $CURRENT_VERSION)")
            }

            val eventsArray = jsonObject["events"]?.jsonArray
                ?: throw IOException("Queue snapshot missing events array")

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

            QueueSnapshot(version = version, events = events)
        } catch (e: Exception) {
            Logger.warn("Queue snapshot unreadable, deleting: ${e.message}")
            try { delete() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException("Queue snapshot unreadable", e)
        }
        return parsed
    }

    /**
     * Delete the snapshot file from disk. No-op when absent; throws on real
     * I/O failure so callers can tell "already gone" from "filesystem said no."
     * The tmp file (if any) is cleaned up best-effort — it's never user-visible.
     */
    @Throws(IOException::class)
    fun delete() {
        if (snapshotFile.exists() && !snapshotFile.delete()) {
            throw IOException("Failed to delete queue snapshot: $snapshotFile")
        }
        if (tmpFile.exists()) {
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

}
