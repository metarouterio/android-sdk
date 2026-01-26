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
 * Relies on SharedPreferences' built-in thread safety and caching.
 */
class IdentityManager(
    private val storage: IdentityStorage
) {
    constructor(context: Context) : this(IdentityStorage(context))

    suspend fun getAnonymousId(): String {
        storage.get(KEY_ANONYMOUS_ID)?.let { return it }

        val newId = UUID.randomUUID().toString()
        storage.set(KEY_ANONYMOUS_ID, newId)
        Logger.log("Generated new anonymous ID: ${newId.take(8)}***")
        return newId
    }

    suspend fun getUserId(): String? = storage.get(KEY_USER_ID)

    suspend fun setUserId(userId: String): Boolean {
        if (userId.isBlank()) {
            Logger.warn("Cannot set empty user ID")
            return false
        }
        return storage.set(KEY_USER_ID, userId)
    }

    suspend fun getGroupId(): String? = storage.get(KEY_GROUP_ID)

    suspend fun setGroupId(groupId: String): Boolean {
        if (groupId.isBlank()) {
            Logger.warn("Cannot set empty group ID")
            return false
        }
        return storage.set(KEY_GROUP_ID, groupId)
    }

    suspend fun getAdvertisingId(): String? = storage.get(KEY_ADVERTISING_ID)

    suspend fun setAdvertisingId(advertisingId: String): Boolean {
        if (advertisingId.isBlank()) {
            Logger.warn("Cannot set empty advertising ID")
            return false
        }
        return storage.set(KEY_ADVERTISING_ID, advertisingId)
    }

    suspend fun clearAdvertisingId() {
        storage.remove(KEY_ADVERTISING_ID)
        Logger.log("Advertising ID cleared")
    }

    suspend fun reset() {
        storage.clear()
        Logger.log("Identity manager reset")
    }
}
