package com.metarouter.analytics.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Thread-safe persistent storage for identity information.
 *
 * Wraps SharedPreferences to store anonymous ID, user ID, group ID, and advertising ID.
 *
 * Write Strategy:
 * - Setters use commit() for durability guarantees (synchronous, blocking)
 * - Clears use apply() for performance (asynchronous, non-blocking)
 *
 * Storage keys follow the spec:
 * - metarouter:anonymous_id
 * - metarouter:user_id
 * - metarouter:group_id
 * - metarouter:advertising_id
 */
class IdentityStorage(context: Context) {

    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Get the stored anonymous ID, or null if not set.
     */
    fun getAnonymousId(): String? {
        return preferences.getString(KEY_ANONYMOUS_ID, null)
    }

    /**
     * Store the anonymous ID with synchronous commit for durability.
     * Uses commit() to ensure the write is persisted to disk before returning.
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

    /**
     * Get the stored user ID, or null if not set.
     */
    fun getUserId(): String? {
        return preferences.getString(KEY_USER_ID, null)
    }

    /**
     * Store the user ID with synchronous commit for durability.
     * Uses commit() to ensure the write is persisted to disk before returning.
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

    /**
     * Clear the user ID from storage using asynchronous apply().
     * Uses apply() for better performance as clear operations are less critical than sets.
     * @return true (always, as apply() doesn't report failures)
     */
    fun clearUserId(): Boolean {
        preferences.edit()
            .remove(KEY_USER_ID)
            .apply()
        return true
    }

    /**
     * Get the stored group ID, or null if not set.
     */
    fun getGroupId(): String? {
        return preferences.getString(KEY_GROUP_ID, null)
    }

    /**
     * Store the group ID with synchronous commit for durability.
     * Uses commit() to ensure the write is persisted to disk before returning.
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

    /**
     * Clear the group ID from storage using asynchronous apply().
     * Uses apply() for better performance as clear operations are less critical than sets.
     * @return true (always, as apply() doesn't report failures)
     */
    fun clearGroupId(): Boolean {
        preferences.edit()
            .remove(KEY_GROUP_ID)
            .apply()
        return true
    }

    /**
     * Get the stored advertising ID, or null if not set.
     */
    fun getAdvertisingId(): String? {
        return preferences.getString(KEY_ADVERTISING_ID, null)
    }

    /**
     * Store the advertising ID with synchronous commit for durability.
     * Uses commit() to ensure the write is persisted to disk before returning.
     * @param advertisingId The advertising ID to store (must not be empty)
     * @return true if stored successfully, false otherwise
     */
    fun setAdvertisingId(advertisingId: String): Boolean {
        if (advertisingId.isBlank()) {
            return false
        }
        return preferences.edit()
            .putString(KEY_ADVERTISING_ID, advertisingId)
            .commit()
    }

    /**
     * Clear the advertising ID from storage using asynchronous apply().
     * Used when user opts out of ad tracking (GDPR/CCPA compliance).
     * Uses apply() for better performance as clear operations are less critical than sets.
     * @return true (always, as apply() doesn't report failures)
     */
    fun clearAdvertisingId(): Boolean {
        preferences.edit()
            .remove(KEY_ADVERTISING_ID)
            .apply()
        return true
    }

    /**
     * Clear all identity data from storage using asynchronous apply().
     * Used during reset() to completely clear SDK state.
     * Uses apply() for better performance as clear operations are less critical than sets.
     * @return true (always, as apply() doesn't report failures)
     */
    fun clearAll(): Boolean {
        preferences.edit()
            .remove(KEY_ANONYMOUS_ID)
            .remove(KEY_USER_ID)
            .remove(KEY_GROUP_ID)
            .remove(KEY_ADVERTISING_ID)
            .apply()
        return true
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics"

        // Storage keys as defined in spec v1.3.0
        const val KEY_ANONYMOUS_ID = "metarouter:anonymous_id"
        const val KEY_USER_ID = "metarouter:user_id"
        const val KEY_GROUP_ID = "metarouter:group_id"
        const val KEY_ADVERTISING_ID = "metarouter:advertising_id"
    }
}
