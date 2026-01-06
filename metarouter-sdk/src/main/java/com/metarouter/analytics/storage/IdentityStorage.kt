package com.metarouter.analytics.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Thread-safe persistent storage for identity information.
 *
 * Wraps SharedPreferences to store anonymous ID, user ID, and group ID.
 *
 * Write Strategy:
 * - Setters use commit() for durability guarantees (synchronous, blocking)
 * - Clears use apply() for performance (asynchronous, non-blocking)
 *
 * Storage keys follow the spec:
 * - metarouter:anonymous_id
 * - metarouter:user_id
 * - metarouter:group_id
 */
class IdentityStorage(context: Context) {

    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getAnonymousId(): String? {
        return preferences.getString(KEY_ANONYMOUS_ID, null)
    }

    /**
     * @param anonymousId The anonymous ID to store (must not be empty)
     * @return true if stored successfully, false otherwise
     */
    fun setAnonymousId(anonymousId: String): Boolean {
        if (anonymousId.isBlank()) {
            return false
        }
        return preferences.edit()
            .putString(KEY_ANONYMOUS_ID, anonymousId)
            .commit()
    }

    fun getUserId(): String? {
        return preferences.getString(KEY_USER_ID, null)
    }

    /**
     * @param userId The user ID to store (must not be empty)
     * @return true if stored successfully, false otherwise
     */
    fun setUserId(userId: String): Boolean {
        if (userId.isBlank()) {
            return false
        }
        return preferences.edit()
            .putString(KEY_USER_ID, userId)
            .commit()
    }

    fun clearUserId(): Boolean {
        preferences.edit()
            .remove(KEY_USER_ID)
            .apply()
        return true
    }

    fun getGroupId(): String? {
        return preferences.getString(KEY_GROUP_ID, null)
    }

    /**
     * @param groupId The group ID to store (must not be empty)
     * @return true if stored successfully, false otherwise
     */
    fun setGroupId(groupId: String): Boolean {
        if (groupId.isBlank()) {
            return false
        }
        return preferences.edit()
            .putString(KEY_GROUP_ID, groupId)
            .commit()
    }

    fun clearGroupId(): Boolean {
        preferences.edit()
            .remove(KEY_GROUP_ID)
            .apply()
        return true
    }

    /** Clear all identity data from storage. Used during reset(). */
    fun clearAll(): Boolean {
        preferences.edit()
            .remove(KEY_ANONYMOUS_ID)
            .remove(KEY_USER_ID)
            .remove(KEY_GROUP_ID)
            .apply()
        return true
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics"

        // Storage keys as defined in spec v1.3.0
        const val KEY_ANONYMOUS_ID = "metarouter:anonymous_id"
        const val KEY_USER_ID = "metarouter:user_id"
        const val KEY_GROUP_ID = "metarouter:group_id"
    }
}
