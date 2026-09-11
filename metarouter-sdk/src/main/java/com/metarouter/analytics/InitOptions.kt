package com.metarouter.analytics

import com.metarouter.analytics.utils.Logger
import com.metarouter.analytics.utils.LoopbackHost
import java.net.URL

/**
 * Why construction was invalid. Recorded on the options rather than thrown: a throwing
 * constructor would leave the diagnostics callback nowhere to live, and an integrator
 * sourcing config from a remote system would wrap the construction in a bare try/catch
 * (or not) straight back into the crash this contract exists to prevent.
 * `MetaRouter.initialize` reads the verdict and degrades.
 */
sealed class ConfigError {
    object EmptyWriteKey : ConfigError()
    data class InvalidIngestionHost(val raw: String) : ConfigError()

    val description: String
        get() = when (this) {
            is EmptyWriteKey -> "writeKey must not be empty or whitespace-only"
            is InvalidIngestionHost -> "ingestionHost must be an http(s) URL, got \"$raw\""
        }
}

/**
 * Configuration options for initializing the MetaRouter Analytics SDK.
 *
 * Construction never fails. Invalid input is recorded in [configError] instead of
 * thrown; values are stored normalized (trimmed writeKey and host, trailing slash
 * removed, numeric bounds clamped with a warning). `MetaRouter.initialize` reads the
 * verdict: debuggable builds fail fast there, release builds log an always-on error,
 * fire [onConfigError], and leave the SDK inert for the session — a misconfigured SDK
 * must never take down the host app.
 *
 * Not a `data class` because normalization happens at construction — the stored
 * properties deliberately differ from the raw constructor arguments. [copy] is kept
 * with the data-class signature. The primary constructor is private and takes
 * already-normalized values, so internal copies (see [discardingConfigCallback]) do
 * not re-run validation and re-log its warnings.
 *
 * @property writeKey API key for authentication (required, non-empty)
 * @property ingestionHost Backend endpoint URL (required, http(s) with a host)
 * @property flushIntervalSeconds Interval in seconds between automatic flushes (default: 10)
 * @property debug Enable debug logging (default: false)
 * @property maxQueueEvents Maximum enriched events held in queue (default: 2000).
 *   This value also determines the incoming event channel capacity (minimum 100).
 *   When limits are exceeded, oldest events are dropped.
 * @property maxDiskEvents Maximum events stored on disk during extended offline periods
 *   (default: 10000). Set to `0` to opt out of disk persistence entirely — the queue then
 *   operates as a purely in-memory ring buffer, dropping the oldest event when full.
 * @property trackLifecycleEvents Whether the SDK should automatically emit
 *   `Application Installed`, `Application Updated`, `Application Opened`, and
 *   `Application Backgrounded` events (default: `false` — opt-in). Set to `true`
 *   to enable. Existing customers upgrading the SDK do not begin emitting
 *   lifecycle events without explicitly enabling the flag.
 * @property sessionTimeoutMinutes Minutes of inactivity after which the next
 *   event starts a new session (default: 30). Session stamping itself is
 *   always on; this only tunes the window.
 * @property fireSessionStarted When `true`, a `Session Started` track event is
 *   emitted each time a new session is minted. Off by default so upgrading
 *   never changes a customer's event volume — matching the web SDK's
 *   `fireSessionStarted`.
 * @property configError Non-null when construction received invalid config. The SDK
 *   never crashes the host over local config in release — `MetaRouter.initialize`
 *   sees this, logs an always-on error, fires [onConfigError], and leaves the SDK
 *   inert for the session, mirroring the 401/403/404 graceful-disable. Debuggable
 *   builds fail fast at the initialize site instead (construction has no `Context`,
 *   so debuggability cannot be read here).
 * @property onConfigError Fired synchronously by `MetaRouter.initialize` (caller's
 *   thread) when [configError] is non-null — the programmatic complement to the error
 *   log, so a host can surface misconfiguration to its own diagnostics.
 */
class InitOptions private constructor(
    val writeKey: String,
    val ingestionHost: String,
    val flushIntervalSeconds: Int,
    val debug: Boolean,
    val maxQueueEvents: Int,
    val maxDiskEvents: Int,
    val trackLifecycleEvents: Boolean,
    val sessionTimeoutMinutes: Int,
    val fireSessionStarted: Boolean,
    val configError: ConfigError?,
    val onConfigError: ((ConfigError) -> Unit)?
) {

    constructor(
        writeKey: String,
        ingestionHost: String,
        flushIntervalSeconds: Int = 10,
        debug: Boolean = false,
        maxQueueEvents: Int = 2000,
        maxDiskEvents: Int = 10000,
        trackLifecycleEvents: Boolean = false,
        sessionTimeoutMinutes: Int = 30,
        fireSessionStarted: Boolean = false,
        onConfigError: ((ConfigError) -> Unit)? = null
    ) : this(
        validate(writeKey, ingestionHost, flushIntervalSeconds, maxQueueEvents, maxDiskEvents, sessionTimeoutMinutes),
        debug,
        trackLifecycleEvents,
        fireSessionStarted,
        onConfigError
    )

    private constructor(
        validated: Validated,
        debug: Boolean,
        trackLifecycleEvents: Boolean,
        fireSessionStarted: Boolean,
        onConfigError: ((ConfigError) -> Unit)?
    ) : this(
        writeKey = validated.writeKey,
        ingestionHost = validated.ingestionHost,
        flushIntervalSeconds = validated.flushIntervalSeconds,
        debug = debug,
        maxQueueEvents = validated.maxQueueEvents,
        maxDiskEvents = validated.maxDiskEvents,
        trackLifecycleEvents = trackLifecycleEvents,
        sessionTimeoutMinutes = validated.sessionTimeoutMinutes,
        fireSessionStarted = fireSessionStarted,
        configError = validated.configError,
        onConfigError = onConfigError
    )

    fun copy(
        writeKey: String = this.writeKey,
        ingestionHost: String = this.ingestionHost,
        flushIntervalSeconds: Int = this.flushIntervalSeconds,
        debug: Boolean = this.debug,
        maxQueueEvents: Int = this.maxQueueEvents,
        maxDiskEvents: Int = this.maxDiskEvents,
        trackLifecycleEvents: Boolean = this.trackLifecycleEvents,
        sessionTimeoutMinutes: Int = this.sessionTimeoutMinutes,
        fireSessionStarted: Boolean = this.fireSessionStarted,
        onConfigError: ((ConfigError) -> Unit)? = this.onConfigError
    ): InitOptions = InitOptions(
        writeKey = writeKey,
        ingestionHost = ingestionHost,
        flushIntervalSeconds = flushIntervalSeconds,
        debug = debug,
        maxQueueEvents = maxQueueEvents,
        maxDiskEvents = maxDiskEvents,
        trackLifecycleEvents = trackLifecycleEvents,
        sessionTimeoutMinutes = sessionTimeoutMinutes,
        fireSessionStarted = fireSessionStarted,
        onConfigError = onConfigError
    )

    /**
     * The callback only ever fires from the config gate, before a client exists —
     * the client's copy of the options must not pin the closure (and whatever host
     * state it captures) for the whole session.
     *
     * Copies the already-normalized fields through the raw constructor: routing
     * through the validating one would re-log the construction-time warnings
     * (cleartext host, disk-below-queue) a second time at the config gate.
     */
    internal fun discardingConfigCallback(): InitOptions = InitOptions(
        writeKey = writeKey,
        ingestionHost = ingestionHost,
        flushIntervalSeconds = flushIntervalSeconds,
        debug = debug,
        maxQueueEvents = maxQueueEvents,
        maxDiskEvents = maxDiskEvents,
        trackLifecycleEvents = trackLifecycleEvents,
        sessionTimeoutMinutes = sessionTimeoutMinutes,
        fireSessionStarted = fireSessionStarted,
        configError = configError,
        onConfigError = null
    )

    /**
     * Get the ingestion host with trailing slash removed (if present).
     * This is a convenience method to ensure consistent URL construction.
     */
    fun getNormalizedIngestionHost(): String = ingestionHost.trimEnd('/')

    /**
     * Get the flush interval in milliseconds for internal use.
     */
    fun getFlushIntervalMillis(): Long = flushIntervalSeconds * 1000L

    /** Normalized construction inputs plus the validation verdict. */
    private class Validated(
        val writeKey: String,
        val ingestionHost: String,
        val flushIntervalSeconds: Int,
        val maxQueueEvents: Int,
        val maxDiskEvents: Int,
        val sessionTimeoutMinutes: Int,
        val configError: ConfigError?
    )

    private companion object {

        /**
         * Single validation funnel, run exactly once per user-supplied construction.
         * Records the first error (writeKey, then host) instead of throwing; numeric
         * bounds clamp with a warning — one policy for all four fields, where
         * previously all were process-killing requires.
         */
        fun validate(
            writeKey: String,
            ingestionHost: String,
            flushIntervalSeconds: Int,
            maxQueueEvents: Int,
            maxDiskEvents: Int,
            sessionTimeoutMinutes: Int
        ): Validated {
            var error: ConfigError? = null

            val trimmedKey = writeKey.trim()
            if (trimmedKey.isEmpty()) {
                error = ConfigError.EmptyWriteKey
            }

            val normalizedHost = ingestionHost.trim().trimEnd('/')
            val parsedHost = parseHttpUrl(normalizedHost)
            if (parsedHost == null) {
                if (error == null) {
                    error = ConfigError.InvalidIngestionHost(ingestionHost)
                }
            } else if (parsedHost.protocol == "http" && !LoopbackHost.isLoopback(parsedHost.host)) {
                // A cleartext origin is spoofable in transit — legitimate for local
                // development, worth a warning anywhere else.
                Logger.warn(
                    "ingestionHost $normalizedHost is cleartext http — use https " +
                        "outside local development."
                )
            }

            if (flushIntervalSeconds < 1) {
                Logger.warn("flushIntervalSeconds ($flushIntervalSeconds) clamped to 1")
            }
            if (maxQueueEvents < 1) {
                Logger.warn("maxQueueEvents ($maxQueueEvents) clamped to 1")
            }
            if (maxDiskEvents < 0) {
                Logger.warn("maxDiskEvents ($maxDiskEvents) clamped to 0 — use 0 to disable disk persistence")
            }
            if (sessionTimeoutMinutes < 1) {
                Logger.warn("sessionTimeoutMinutes ($sessionTimeoutMinutes) clamped to 1")
            }

            val clampedQueue = maxOf(1, maxQueueEvents)
            val clampedDisk = maxOf(0, maxDiskEvents)
            if (clampedDisk in 1 until clampedQueue) {
                Logger.warn(
                    "maxDiskEvents ($clampedDisk) is less than maxQueueEvents ($clampedQueue) — " +
                        "memory can hold more events than disk can preserve; events may be dropped during background flush."
                )
            }

            return Validated(
                writeKey = trimmedKey,
                ingestionHost = normalizedHost,
                flushIntervalSeconds = maxOf(1, flushIntervalSeconds),
                maxQueueEvents = clampedQueue,
                maxDiskEvents = clampedDisk,
                sessionTimeoutMinutes = maxOf(1, sessionTimeoutMinutes),
                configError = error
            )
        }

        fun parseHttpUrl(value: String): URL? {
            // The scheme is matched case-insensitively (RFC 3986), and the host must be
            // non-empty: "https:/" (what a bare "https://" becomes after the slash trim)
            // parses as a scheme with no host, which would build a live client that
            // fails every request at network time.
            val colon = value.indexOf(':')
            if (colon <= 0) return null
            val scheme = value.substring(0, colon).lowercase()
            if (scheme != "http" && scheme != "https") return null
            return try {
                val url = URL(scheme + value.substring(colon))
                if (url.host.isNullOrEmpty()) null else url
            } catch (e: Exception) {
                null
            }
        }
    }
}
