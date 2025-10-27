package com.metarouter.analytics.utils

import android.util.Log

/**
 * Thread-safe logging utility for the MetaRouter SDK.
 *
 * Provides three log levels:
 * - log: Debug information (only when debug enabled)
 * - warn: Warnings (always logged)
 * - error: Errors (always logged)
 *
 * All messages use the "MetaRouter" tag for easy filtering in logcat.
 */
object Logger {
    private const val TAG = "MetaRouter"

    /**
     * Enable debug logging. When false, only warnings and errors are logged.
     */
    @Volatile
    var debugEnabled: Boolean = false

    /**
     * Log debug information. Only logged when debugEnabled is true.
     *
     * @param message The message to log
     */
    fun log(message: String) {
        if (debugEnabled) {
            Log.d(TAG, message)
        }
    }

    /**
     * Log a warning. Always logged regardless of debug setting.
     *
     * @param message The warning message
     */
    fun warn(message: String) {
        Log.w(TAG, message)
    }

    /**
     * Log an error. Always logged regardless of debug setting.
     *
     * @param message The error message
     * @param throwable Optional exception for stack trace
     */
    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    /**
     * Log contextual debug information with writeKey and host.
     *
     * @param message The message to log
     * @param writeKey The write key (will be masked)
     * @param host The ingestion host
     */
    fun logWithContext(message: String, writeKey: String, host: String) {
        if (debugEnabled) {
            val maskedKey = maskWriteKey(writeKey)
            log("$message [writeKey=$maskedKey, host=$host]")
        }
    }

    /**
     * Mask a write key for logging, showing only the last 4 characters.
     * Example: "abc123xyz" -> "***3xyz"
     *
     * @param writeKey The write key to mask
     * @return Masked write key
     */
    fun maskWriteKey(writeKey: String): String {
        return if (writeKey.length <= 4) {
            "***"
        } else {
            "***${writeKey.takeLast(4)}"
        }
    }

    /**
     * Redact PII for logging (e.g., advertising ID).
     * Shows first 8 characters only.
     * Example: "12345678-1234-1234-1234-123456789abc" -> "12345678***"
     *
     * @param value The value to redact
     * @return Redacted value
     */
    fun redactPII(value: String): String {
        return if (value.length <= 8) {
            "***"
        } else {
            "${value.take(8)}***"
        }
    }
}
