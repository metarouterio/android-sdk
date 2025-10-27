package com.metarouter.analytics.utils

import java.util.UUID

/**
 * Generates unique message IDs for events.
 *
 * Format: {timestamp-ms}-{uuid}
 * Example: 1697545200000-a1b2c3d4-e5f6-7890-abcd-ef1234567890
 *
 * The timestamp prefix aids in debugging by showing when the event was created.
 */
object MessageIdGenerator {
    /**
     * Generate a unique message ID with current timestamp.
     *
     * @return Message ID in format "{timestamp}-{uuid}"
     */
    fun generate(): String {
        return generate(System.currentTimeMillis())
    }

    /**
     * Generate a unique message ID with custom timestamp.
     *
     * @param timestampMs Timestamp in milliseconds since epoch
     * @return Message ID in format "{timestamp}-{uuid}"
     */
    fun generate(timestampMs: Long): String {
        val uuid = UUID.randomUUID().toString()
        return "$timestampMs-$uuid"
    }

    /**
     * Extract the timestamp from a message ID.
     *
     * @param messageId The message ID to parse
     * @return Timestamp in milliseconds, or null if invalid format
     */
    fun extractTimestamp(messageId: String): Long? {
        return try {
            val parts = messageId.split("-", limit = 2)
            if (parts.isNotEmpty()) {
                parts[0].toLongOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validate that a message ID has the correct format.
     *
     * @param messageId The message ID to validate
     * @return True if valid format, false otherwise
     */
    fun isValid(messageId: String): Boolean {
        if (messageId.isEmpty()) return false

        val parts = messageId.split("-")
        if (parts.size != 6) return false // timestamp + 5 UUID parts

        // First part should be a valid timestamp
        val timestamp = parts[0].toLongOrNull() ?: return false
        if (timestamp <= 0) return false

        // Remaining parts should form a valid UUID
        val uuidPart = parts.drop(1).joinToString("-")
        return try {
            UUID.fromString(uuidPart)
            true
        } catch (e: Exception) {
            false
        }
    }
}
