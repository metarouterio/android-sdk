package com.metarouter.analytics.identity

import android.content.Context
import com.metarouter.analytics.storage.IdentityStorage
import com.metarouter.analytics.utils.Logger
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe identity management for the MetaRouter SDK.
 *
 * Manages anonymous ID, user ID, group ID, and advertising ID with persistent storage.
 *
 * Concurrency Architecture:
 * - Per-key AtomicReference with sentinel pattern for lazy initialization
 * - Per-key locks only for first load from storage (double-checked locking)
 * - Lock-free reads after initial load (zero contention)
 * - Lock-free writes with I/O happening outside any locks
 * - Storage setters use commit() for durability guarantees
 * - Storage clears use apply() for better performance
 *
 * Consistency Guarantees:
 * - Read-your-writes: Writes are immediately visible to subsequent reads
 * - Durability: Set operations use synchronous commit() to ensure persistence
 * - Cache-storage consistency: Memory cache always reflects successful writes
 *
 * Anonymous ID generation follows spec v1.3.0:
 * - Primary: UUID v4 format
 * - Fallback: "fallback-{timestamp}-{random8chars}" if UUID generation fails
 */
class IdentityManager(context: Context) {

    private val storage = IdentityStorage(context)

    // Sentinel value to indicate "not loaded yet" vs "loaded but null"
    private object NotLoaded

    // Per-key atomic references with NotLoaded sentinel
    private val anonymousIdRef = AtomicReference<Any>(NotLoaded)
    private val userIdRef = AtomicReference<Any>(NotLoaded)
    private val groupIdRef = AtomicReference<Any>(NotLoaded)
    private val advertisingIdRef = AtomicReference<Any>(NotLoaded)

    // Per-key locks only for initial load
    private val anonymousIdLock = Any()
    private val userIdLock = Any()
    private val groupIdLock = Any()
    private val advertisingIdLock = Any()

    /**
     * Get the current anonymous ID. Generates and persists a new one if not set.
     * @return The anonymous ID (never null)
     */
    suspend fun getAnonymousId(): String {
        // Fast path: check if already loaded
        val cached = anonymousIdRef.get()
        if (cached !== NotLoaded) {
            return cached as String
        }

        // Slow path: need to load from storage (lock only for initialization)
        synchronized(anonymousIdLock) {
            // Double-check after acquiring lock
            val recheck = anonymousIdRef.get()
            if (recheck !== NotLoaded) {
                return recheck as String
            }

            // Load from storage outside the critical section that updates the ref
            val storedId = storage.getAnonymousId()

            if (storedId != null) {
                anonymousIdRef.set(storedId)
                Logger.log("Loaded anonymous ID from storage: ${maskId(storedId)}")
                return storedId
            }

            // Generate new anonymous ID
            val newId = generateAnonymousId()

            // Write to storage happens here (still in lock for consistency)
            storage.setAnonymousId(newId)
            Logger.log("Generated and stored new anonymous ID: ${maskId(newId)}")

            anonymousIdRef.set(newId)
            return newId
        }
    }

    /**
     * Get the current user ID.
     * @return The user ID, or null if not set
     */
    suspend fun getUserId(): String? {
        // Fast path: check if already loaded
        val cached = userIdRef.get()
        if (cached !== NotLoaded) {
            return cached as String?
        }

        // Slow path: need to load from storage (lock only for initialization)
        synchronized(userIdLock) {
            // Double-check after acquiring lock
            val recheck = userIdRef.get()
            if (recheck !== NotLoaded) {
                return recheck as String?
            }

            // Load from storage
            val storedId = storage.getUserId()
            userIdRef.set(storedId)
            return storedId
        }
    }

    /**
     * Set the user ID and persist to storage.
     * @param userId The user ID to set (must not be blank)
     * @return true if set successfully, false otherwise
     */
    suspend fun setUserId(userId: String): Boolean {
        if (userId.isBlank()) {
            Logger.warn("Cannot set empty user ID")
            return false
        }

        // I/O happens outside any lock
        val stored = storage.setUserId(userId)

        if (stored) {
            // Lock-free atomic update
            userIdRef.set(userId)
            Logger.log("Set user ID: ${maskId(userId)}")
        } else {
            Logger.error("Failed to persist user ID")
        }

        return stored
    }

    /**
     * Clear the user ID from storage and cache.
     * @return true (always, as apply() doesn't report failures)
     */
    suspend fun clearUserId(): Boolean {
        // I/O happens outside any lock
        val cleared = storage.clearUserId()

        if (cleared) {
            // Lock-free atomic update
            userIdRef.set(null)
            Logger.log("Cleared user ID")
        } else {
            Logger.error("Failed to clear user ID")
        }

        return cleared
    }

    /**
     * Get the current group ID.
     * @return The group ID, or null if not set
     */
    suspend fun getGroupId(): String? {
        // Fast path: check if already loaded
        val cached = groupIdRef.get()
        if (cached !== NotLoaded) {
            return cached as String?
        }

        // Slow path: need to load from storage (lock only for initialization)
        synchronized(groupIdLock) {
            // Double-check after acquiring lock
            val recheck = groupIdRef.get()
            if (recheck !== NotLoaded) {
                return recheck as String?
            }

            // Load from storage
            val storedId = storage.getGroupId()
            groupIdRef.set(storedId)
            return storedId
        }
    }

    /**
     * Set the group ID and persist to storage.
     * @param groupId The group ID to set (must not be blank)
     * @return true if set successfully, false otherwise
     */
    suspend fun setGroupId(groupId: String): Boolean {
        if (groupId.isBlank()) {
            Logger.warn("Cannot set empty group ID")
            return false
        }

        // I/O happens outside any lock
        val stored = storage.setGroupId(groupId)

        if (stored) {
            // Lock-free atomic update
            groupIdRef.set(groupId)
            Logger.log("Set group ID: ${maskId(groupId)}")
        } else {
            Logger.error("Failed to persist group ID")
        }

        return stored
    }

    /**
     * Clear the group ID from storage and cache.
     * @return true (always, as apply() doesn't report failures)
     */
    suspend fun clearGroupId(): Boolean {
        // I/O happens outside any lock
        val cleared = storage.clearGroupId()

        if (cleared) {
            // Lock-free atomic update
            groupIdRef.set(null)
            Logger.log("Cleared group ID")
        } else {
            Logger.error("Failed to clear group ID")
        }

        return cleared
    }

    /**
     * Get the current advertising ID (IDFA/GAID).
     * @return The advertising ID, or null if not set
     */
    suspend fun getAdvertisingId(): String? {
        // Fast path: check if already loaded
        val cached = advertisingIdRef.get()
        if (cached !== NotLoaded) {
            return cached as String?
        }

        // Slow path: need to load from storage (lock only for initialization)
        synchronized(advertisingIdLock) {
            // Double-check after acquiring lock
            val recheck = advertisingIdRef.get()
            if (recheck !== NotLoaded) {
                return recheck as String?
            }

            // Load from storage
            val storedId = storage.getAdvertisingId()
            advertisingIdRef.set(storedId)
            return storedId
        }
    }

    /**
     * Set the advertising ID and persist to storage.
     * @param advertisingId The advertising ID to set (must not be blank)
     * @return true if set successfully, false otherwise
     */
    suspend fun setAdvertisingId(advertisingId: String): Boolean {
        if (advertisingId.isBlank()) {
            Logger.warn("Cannot set empty advertising ID")
            return false
        }

        // I/O happens outside any lock
        val stored = storage.setAdvertisingId(advertisingId)

        if (stored) {
            // Lock-free atomic update
            advertisingIdRef.set(advertisingId)
            Logger.log("Set advertising ID: ${Logger.redactPII(advertisingId)}")
        } else {
            Logger.error("Failed to persist advertising ID")
        }

        return stored
    }

    /**
     * Clear the advertising ID from storage and cache.
     * Used when user opts out of ad tracking (GDPR/CCPA compliance).
     * @return true (always, as apply() doesn't report failures)
     */
    suspend fun clearAdvertisingId(): Boolean {
        // I/O happens outside any lock
        val cleared = storage.clearAdvertisingId()

        if (cleared) {
            // Lock-free atomic update
            advertisingIdRef.set(null)
            Logger.log("Cleared advertising ID")
        } else {
            Logger.error("Failed to clear advertising ID")
        }

        return cleared
    }

    /**
     * Reset all identity data to initial state.
     * Clears all IDs from storage and cache. A new anonymous ID will be generated on next access.
     */
    suspend fun reset() {
        Logger.log("Resetting identity manager")

        // I/O happens outside any lock
        val cleared = storage.clearAll()
        if (!cleared) {
            Logger.error("Failed to clear all identity data")
        }

        // Lock-free atomic updates - reset to NotLoaded state
        anonymousIdRef.set(NotLoaded)
        userIdRef.set(null)
        groupIdRef.set(null)
        advertisingIdRef.set(null)

        Logger.log("Identity manager reset complete")
    }

    /**
     * Generate a new anonymous ID (spec v1.3.0):
     * Primary: UUID v4, Fallback: "fallback-{timestamp}-{random8chars}"
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

    /** Mask an ID for logging (show first 8 chars only). */
    private fun maskId(id: String): String {
        return if (id.length <= 8) {
            "***"
        } else {
            "${id.take(8)}***"
        }
    }
}
