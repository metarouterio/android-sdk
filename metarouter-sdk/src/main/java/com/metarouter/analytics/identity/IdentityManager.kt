package com.metarouter.analytics.identity

import android.content.Context
import com.metarouter.analytics.storage.IdentityStorage
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_ADVERTISING_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_ANONYMOUS_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_GROUP_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_USER_ID
import com.metarouter.analytics.utils.Logger
import java.util.UUID

/**
 * Identity management for the MetaRouter SDK.
 *
 * Thread-safe via SharedPreferences' built-in synchronization.
 * All operations are non-blocking (uses apply() for writes).
 */
class IdentityManager(
    internal val storage: IdentityStorage
) {
    constructor(context: Context) : this(IdentityStorage(context))

    /**
     * Get or generate the anonymous ID.
     * If no ID exists, generates a new UUID and persists it.
     */
    fun getAnonymousId(): String {
        // Check-and-set under the storage's lock: setAnonymousId can write from the
        // proxy, before a client is bound, while this mints the first id.
        val (id, created) = storage.getOrCreateAnonymousId { UUID.randomUUID().toString() }
        if (created) Logger.log("Generated new anonymous ID: ${id.take(8)}***")
        return id
    }

    /**
     * Replaces the anonymous ID with one the host owns (e.g. the id a WebView's web SDK
     * already uses). See [applyHostAnonymousId].
     */
    fun setAnonymousId(anonymousId: String): Boolean = applyHostAnonymousId(anonymousId, storage)

    /** See [IdentityStorage.noteResetRequested]. */
    internal fun noteResetRequested() = storage.noteResetRequested()

    /**
     * Ends the window in which a host-set anonymousId doesn't count as prior identity.
     * Called once install detection has read [hasAnyValue].
     */
    internal fun clearHostSetMarker() = storage.clearHostSetMarker()

    fun getUserId(): String? = storage.get(KEY_USER_ID)

    /**
     * Set the user ID.
     * @return true if the ID was set, false if the ID was blank
     */
    fun setUserId(userId: String): Boolean {
        if (userId.isBlank()) {
            Logger.warn("Cannot set empty user ID")
            return false
        }
        storage.set(KEY_USER_ID, userId)
        return true
    }

    fun getGroupId(): String? = storage.get(KEY_GROUP_ID)

    /**
     * Set the group ID.
     * @return true if the ID was set, false if the ID was blank
     */
    fun setGroupId(groupId: String): Boolean {
        if (groupId.isBlank()) {
            Logger.warn("Cannot set empty group ID")
            return false
        }
        storage.set(KEY_GROUP_ID, groupId)
        return true
    }

    suspend fun getAdvertisingId(): String? = storage.get(KEY_ADVERTISING_ID)

    suspend fun setAdvertisingId(advertisingId: String): Boolean {
        if (advertisingId.isBlank()) {
            Logger.warn("Cannot set empty advertising ID")
            return false
        }
        storage.set(KEY_ADVERTISING_ID, advertisingId)
        return true
    }

    suspend fun clearAdvertisingId() {
        storage.remove(KEY_ADVERTISING_ID)
        Logger.log("Advertising ID cleared")
    }

    suspend fun reset() {
        storage.clear()
        Logger.log("Identity manager reset")
    }

    /**
     * Returns true if identity storage already has any value. Used by lifecycle-event
     * detection to tell a true fresh install from a user upgrading from a pre-lifecycle
     * SDK build (where the version/build keys would be missing despite prior usage).
     */
    fun hasAnyValue(): Boolean = storage.hasAnyValue()

    companion object {
        /**
         * Longest accepted host anonymousId, in UTF-8 bytes. Generous for any web or
         * vendor id (a UUID is 36), and it rides on every event.
         */
        internal const val MAX_HOST_ANONYMOUS_ID_BYTES = 256

        /**
         * The trimmed id, or null (with a warning) when it is rejected. [current] is
         * only for the warning.
         */
        internal fun validateHostAnonymousId(raw: String, current: String?): String? {
            val id = raw.trim()
            val unchanged = "anonymousId unchanged (${current?.let { "${it.take(8)}***" } ?: "none stored yet"})"
            if (id.isEmpty()) {
                Logger.warn("setAnonymousId rejected: the value is empty after trimming whitespace. $unchanged.")
                return null
            }
            val bytes = id.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_HOST_ANONYMOUS_ID_BYTES) {
                Logger.warn(
                    "setAnonymousId rejected: the value is $bytes bytes, over the " +
                        "$MAX_HOST_ANONYMOUS_ID_BYTES-byte limit (starts '${id.take(40)}'). $unchanged."
                )
                return null
            }
            return id
        }

        /**
         * Validates and stores a host-provided anonymousId. Shared by the proxy (which
         * writes before any client exists) and the client.
         * @return false when rejected; the stored anonymousId is unchanged.
         */
        internal fun applyHostAnonymousId(raw: String, storage: IdentityStorage): Boolean {
            val id = validateHostAnonymousId(raw, storage.get(KEY_ANONYMOUS_ID)) ?: return false
            val (previous, changed) = storage.setHostAnonymousId(id)
            if (changed) {
                Logger.log("Anonymous ID set by host: ${id.take(8)}*** (was ${previous?.let { "${it.take(8)}***" } ?: "none stored yet"})")
            }
            return true
        }
    }
}
