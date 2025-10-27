package com.metarouter.analytics.identity

import android.content.Context
import com.metarouter.analytics.storage.IdentityStorage
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Thread-safe identity management for the MetaRouter SDK.
 *
 * Manages anonymous ID, user ID, group ID, and advertising ID with persistent storage.
 * All operations are thread-safe using Kotlin coroutines Mutex.
 *
 * Anonymous ID generation follows spec v1.3.0:
 * - Primary: UUID v4 format
 * - Fallback: "fallback-{timestamp}-{random8chars}" if UUID generation fails
 */
class IdentityManager(context: Context) {

    private val storage = IdentityStorage(context)
    private val mutex = Mutex()

    // In-memory cache for performance
    @Volatile
    private var cachedAnonymousId: String? = null
    @Volatile
    private var cachedUserId: String? = null
    @Volatile
    private var cachedGroupId: String? = null
    @Volatile
    private var cachedAdvertisingId: String? = null

    /**
     * Get the current anonymous ID. If not set, generates and persists a new one.
     * This method is thread-safe and idempotent.
     *
     * @return The anonymous ID (never null)
     */
    suspend fun getAnonymousId(): String = mutex.withLock {
        // Return cached value if available
        cachedAnonymousId?.let { return it }

        // Try to load from storage
        val storedId = storage.getAnonymousId()
        if (storedId != null) {
            cachedAnonymousId = storedId
            Logger.log("Loaded anonymous ID from storage: ${maskId(storedId)}")
            return storedId
        }

        // Generate new anonymous ID
        val newId = generateAnonymousId()
        val stored = storage.setAnonymousId(newId)

        if (stored) {
            cachedAnonymousId = newId
            Logger.log("Generated and stored new anonymous ID: ${maskId(newId)}")
        } else {
            Logger.warn("Failed to persist anonymous ID, using in-memory only")
            cachedAnonymousId = newId
        }

        return newId
    }

    /**
     * Get the current user ID, or null if not set.
     * This method is thread-safe.
     *
     * @return The user ID, or null if not identified
     */
    suspend fun getUserId(): String? = mutex.withLock {
        // Return cached value if available
        cachedUserId?.let { return it }

        // Load from storage
        val storedId = storage.getUserId()
        cachedUserId = storedId
        return storedId
    }

    /**
     * Set the user ID and persist to storage.
     * Called by identify() and alias() methods.
     * This method is thread-safe.
     *
     * @param userId The user ID to set (must not be blank)
     * @return true if set successfully, false otherwise
     */
    suspend fun setUserId(userId: String): Boolean = mutex.withLock {
        if (userId.isBlank()) {
            Logger.warn("Cannot set empty user ID")
            return false
        }

        val stored = storage.setUserId(userId)
        if (stored) {
            cachedUserId = userId
            Logger.log("Set user ID: ${maskId(userId)}")
        } else {
            Logger.error("Failed to persist user ID")
        }

        return stored
    }

    /**
     * Clear the user ID from storage and cache.
     * Used during reset().
     * This method is thread-safe.
     *
     * @return true if cleared successfully, false otherwise
     */
    suspend fun clearUserId(): Boolean = mutex.withLock {
        val cleared = storage.clearUserId()
        if (cleared) {
            cachedUserId = null
            Logger.log("Cleared user ID")
        } else {
            Logger.error("Failed to clear user ID")
        }
        return cleared
    }

    /**
     * Get the current group ID, or null if not set.
     * This method is thread-safe.
     *
     * @return The group ID, or null if not set
     */
    suspend fun getGroupId(): String? = mutex.withLock {
        // Return cached value if available
        cachedGroupId?.let { return it }

        // Load from storage
        val storedId = storage.getGroupId()
        cachedGroupId = storedId
        return storedId
    }

    /**
     * Set the group ID and persist to storage.
     * Called by group() method.
     * This method is thread-safe.
     *
     * @param groupId The group ID to set (must not be blank)
     * @return true if set successfully, false otherwise
     */
    suspend fun setGroupId(groupId: String): Boolean = mutex.withLock {
        if (groupId.isBlank()) {
            Logger.warn("Cannot set empty group ID")
            return false
        }

        val stored = storage.setGroupId(groupId)
        if (stored) {
            cachedGroupId = groupId
            Logger.log("Set group ID: ${maskId(groupId)}")
        } else {
            Logger.error("Failed to persist group ID")
        }

        return stored
    }

    /**
     * Clear the group ID from storage and cache.
     * Used during reset().
     * This method is thread-safe.
     *
     * @return true if cleared successfully, false otherwise
     */
    suspend fun clearGroupId(): Boolean = mutex.withLock {
        val cleared = storage.clearGroupId()
        if (cleared) {
            cachedGroupId = null
            Logger.log("Cleared group ID")
        } else {
            Logger.error("Failed to clear group ID")
        }
        return cleared
    }

    /**
     * Get the current advertising ID, or null if not set.
     * This method is thread-safe.
     *
     * @return The advertising ID (IDFA/GAID), or null if not set
     */
    suspend fun getAdvertisingId(): String? = mutex.withLock {
        // Return cached value if available
        cachedAdvertisingId?.let { return it }

        // Load from storage
        val storedId = storage.getAdvertisingId()
        cachedAdvertisingId = storedId
        return storedId
    }

    /**
     * Set the advertising ID and persist to storage.
     * Called by setAdvertisingId() method.
     * This method is thread-safe.
     *
     * @param advertisingId The advertising ID to set (must not be blank)
     * @return true if set successfully, false otherwise
     */
    suspend fun setAdvertisingId(advertisingId: String): Boolean = mutex.withLock {
        if (advertisingId.isBlank()) {
            Logger.warn("Cannot set empty advertising ID")
            return false
        }

        val stored = storage.setAdvertisingId(advertisingId)
        if (stored) {
            cachedAdvertisingId = advertisingId
            Logger.log("Set advertising ID: ${Logger.redactPII(advertisingId)}")
        } else {
            Logger.error("Failed to persist advertising ID")
        }

        return stored
    }

    /**
     * Clear the advertising ID from storage and cache.
     * Used when user opts out of ad tracking (GDPR/CCPA compliance).
     * This method is thread-safe.
     *
     * @return true if cleared successfully, false otherwise
     */
    suspend fun clearAdvertisingId(): Boolean = mutex.withLock {
        val cleared = storage.clearAdvertisingId()
        if (cleared) {
            cachedAdvertisingId = null
            Logger.log("Cleared advertising ID")
        } else {
            Logger.error("Failed to clear advertising ID")
        }
        return cleared
    }

    /**
     * Reset all identity data to initial state.
     * Clears all IDs from storage and generates a new anonymous ID.
     * Used during reset().
     * This method is thread-safe.
     */
    suspend fun reset() = mutex.withLock {
        Logger.log("Resetting identity manager")

        // Clear all storage
        val cleared = storage.clearAll()
        if (!cleared) {
            Logger.error("Failed to clear all identity data")
        }

        // Clear all caches
        cachedAnonymousId = null
        cachedUserId = null
        cachedGroupId = null
        cachedAdvertisingId = null

        Logger.log("Identity manager reset complete")
    }

    /**
     * Generate a new anonymous ID following spec v1.3.0:
     * - Primary: UUID v4 format
     * - Fallback: "fallback-{timestamp}-{random8chars}" if UUID generation fails
     *
     * @return A new anonymous ID (never null)
     */
    private fun generateAnonymousId(): String {
        return try {
            UUID.randomUUID().toString()
        } catch (e: Exception) {
            // Fallback strategy per spec
            val timestamp = System.currentTimeMillis()
            val random = (1..8)
                .map { ('a'..'z').random() }
                .joinToString("")
            val fallbackId = "fallback-$timestamp-$random"

            Logger.warn("UUID generation failed, using fallback: ${maskId(fallbackId)}")
            fallbackId
        }
    }

    /**
     * Mask an ID for logging (show first 8 chars only).
     *
     * @param id The ID to mask
     * @return Masked ID string
     */
    private fun maskId(id: String): String {
        return if (id.length <= 8) {
            "***"
        } else {
            "${id.take(8)}***"
        }
    }
}
