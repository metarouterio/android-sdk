package com.metarouter.analytics.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Thread-safe persistent storage for identity information.
 *
 * Wraps SharedPreferences to store anonymous ID, user ID, group ID, and advertising ID.
 * All operations are synchronous and thread-safe.
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
     * Store the anonymous ID.
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
     * Store the user ID.
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
     * Clear the user ID from storage.
     * @return true if cleared successfully, false otherwise
     */
    fun clearUserId(): Boolean {
        return preferences.edit()
            .remove(KEY_USER_ID)
            .commit()
    }

    /**
     * Get the stored group ID, or null if not set.
     */
    fun getGroupId(): String? {
        return preferences.getString(KEY_GROUP_ID, null)
    }

    /**
     * Store the group ID.
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
     * Clear the group ID from storage.
     * @return true if cleared successfully, false otherwise
     */
    fun clearGroupId(): Boolean {
        return preferences.edit()
            .remove(KEY_GROUP_ID)
            .commit()
    }

    /**
     * Get the stored advertising ID, or null if not set.
     */
    fun getAdvertisingId(): String? {
        return preferences.getString(KEY_ADVERTISING_ID, null)
    }

    /**
     * Store the advertising ID (IDFA on iOS, GAID on Android).
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
     * Clear the advertising ID from storage.
     * Used when user opts out of ad tracking (GDPR/CCPA compliance).
     * @return true if cleared successfully, false otherwise
     */
    fun clearAdvertisingId(): Boolean {
        return preferences.edit()
            .remove(KEY_ADVERTISING_ID)
            .commit()
    }

    /**
     * Clear all identity data from storage.
     * Used during reset() to completely clear SDK state.
     * @return true if all data cleared successfully, false otherwise
     */
    fun clearAll(): Boolean {
        return preferences.edit()
            .remove(KEY_ANONYMOUS_ID)
            .remove(KEY_USER_ID)
            .remove(KEY_GROUP_ID)
            .remove(KEY_ADVERTISING_ID)
            .commit()
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
