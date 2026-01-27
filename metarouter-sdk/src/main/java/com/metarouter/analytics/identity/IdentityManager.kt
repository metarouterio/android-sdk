package com.metarouter.analytics.identity

import android.content.Context
import com.metarouter.analytics.storage.IdentityStorage
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
    private val storage: IdentityStorage
) {
    constructor(context: Context) : this(IdentityStorage(context))

    /**
     * Get or generate the anonymous ID.
     * If no ID exists, generates a new UUID and persists it.
     */
    fun getAnonymousId(): String {
        storage.get(KEY_ANONYMOUS_ID)?.let { return it }

        val newId = UUID.randomUUID().toString()
        storage.set(KEY_ANONYMOUS_ID, newId)
        Logger.log("Generated new anonymous ID: ${newId.take(8)}***")
        return newId
    }

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

    fun reset() {
        storage.clear()
        Logger.log("Identity manager reset")
    }
}
