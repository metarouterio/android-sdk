package com.metarouter.analytics.network

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parse Retry-After header value to milliseconds.
 *
 * Supports:
 * - Numeric seconds (e.g., "120")
 * - HTTP-date formats per RFC 7231 (e.g., "Wed, 21 Oct 2025 07:28:00 GMT")
 *
 * @param headers Response headers (case-insensitive lookup for Retry-After)
 * @return Delay in milliseconds, or null if header missing or unparseable
 */
fun parseRetryAfterMs(headers: Map<String, String>): Long? {
    val value = headers.entries
        .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.trim()
        ?: return null

    // Try numeric seconds first
    value.toLongOrNull()?.let { seconds ->
        return maxOf(0L, seconds * 1000)
    }

    // Try HTTP-date formats
    return parseHttpDate(value)?.let { date ->
        maxOf(0L, date.time - System.currentTimeMillis())
    }
}

private fun parseHttpDate(value: String): Date? {
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",  // RFC 7231 preferred
        "EEEE, dd-MMM-yy HH:mm:ss zzz",   // RFC 850
        "EEE MMM d HH:mm:ss yyyy"          // ANSI C asctime()
    )

    for (format in formats) {
        try {
            val df = SimpleDateFormat(format, Locale.US)
            df.timeZone = TimeZone.getTimeZone("GMT")
            return df.parse(value)
        } catch (_: ParseException) {
            // Try next format
        }
    }
    return null
}
