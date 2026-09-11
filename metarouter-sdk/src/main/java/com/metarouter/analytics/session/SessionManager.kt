package com.metarouter.analytics.session

import android.os.SystemClock

/**
 * Event emitted when a new session is minted and `fireSessionStarted` is on.
 * The name matches the web SDK's session-management sync verbatim (the
 * pipeline snake_cases it to `session_started` at the mapping layer), so
 * downstream mappings keyed on the event name treat every platform as one.
 */
internal object SessionEventNames {
    const val SESSION_STARTED = "Session Started"
}

/**
 * Snapshot of the session an event belongs to, returned by [SessionManager.touch].
 *
 * @property sessionId Epoch milliseconds of the session's first activity, as a
 *   string — the same format the web SDK's generic MetaRouter session mints, so
 *   pipeline mappings see one shape from every platform.
 * @property sessionCount Lifetime session ordinal for this install, starting at 1.
 * @property startedNewSession True exactly once per session: on the touch that
 *   minted it. Drives the optional `Session Started` event without a second
 *   bookkeeping channel.
 */
internal data class SessionInfo(
    val sessionId: String,
    val sessionCount: Int,
    val startedNewSession: Boolean
)

/**
 * Synchronous handler slot for "a new session was minted".
 *
 * Installed synchronously during client initialization, before the event
 * processor starts consuming — an install that runs after events can flow
 * would race the install's first mint and silently drop the very first
 * `Session Started`, which never recurs for that session.
 */
internal class SessionStartRelay {
    private val lock = Any()
    private var handler: ((SessionInfo) -> Unit)? = null

    fun set(newHandler: (SessionInfo) -> Unit) {
        synchronized(lock) { handler = newHandler }
    }

    /**
     * Fires outside the lock: the handler re-enters the analytics client
     * (which eventually re-enters the session manager), and holding the lock
     * across that would invite deadlock the moment anyone fires from two
     * threads.
     */
    fun fire(info: SessionInfo) {
        val current = synchronized(lock) { handler }
        current?.invoke(info)
    }
}

/**
 * Owns the sliding-inactivity session: mints, extends, and rolls it over.
 *
 * A session ends after [timeoutMinutes] of inactivity (default 30), where
 * activity is any [touch] — one per outbound event. The window slides:
 * activity extends the session indefinitely, matching the web SDK's generic
 * MetaRouter session and GA4's native app-session model, NOT the web GA4
 * injector's from-start cap (which would end a 35-minute active browse
 * mid-flight).
 *
 * Two clocks, on purpose:
 * - In-process inactivity is measured on a monotonic clock. Wall-clock can
 *   jump minutes in either direction (NTP, user changes the time) and would
 *   end or immortalize a session that saw no real inactivity.
 *   `SystemClock.elapsedRealtime` — unlike `uptimeMillis` — keeps counting
 *   across deep sleep, so the window measures real elapsed time.
 * - Across process restarts only wall-clock survives, so the persisted
 *   last-activity is epoch ms. A wall reading from the future (clock was set
 *   back since it was written) is treated as expired: resuming would make the
 *   session immortal — elapsed stays negative until the clock catches up —
 *   while minting costs at most one extra session and self-heals on the next
 *   write.
 *
 * Rollover uses `>` (exactly `timeout` old is still the same session) to stay
 * boundary-identical with the web and iOS implementations.
 */
internal class SessionManager(
    private val storage: SessionStorage,
    timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicClockMillis: () -> Long = SystemClock::elapsedRealtime
) {

    init {
        // Bounds are enforced with clamp-and-warn at the InitOptions boundary;
        // by here a non-positive timeout is SDK-internal misuse, not user input.
        require(timeoutMinutes > 0) { "timeoutMinutes must be > 0" }
    }

    /** Notified on every mint; see [SessionStartRelay] for why it is not a setter. */
    val onSessionStart = SessionStartRelay()

    private val timeoutMillis: Long = timeoutMinutes * 60_000L

    // Guarded by synchronized(this): enrichment touches from the event
    // processor coroutine while diagnostics read from arbitrary threads.
    private var sessionId: String? = null
    private var sessionCount: Int = 0
    private var lastActivityMono: Long = 0L
    private var lastPersistedMono: Long = 0L

    /**
     * Records activity and returns the session it belongs to, minting or
     * rolling over first when the inactivity window has lapsed. Call once per
     * outbound event; the lock serializes concurrent callers so a burst of
     * events at cold start mints exactly one session.
     */
    fun touch(): SessionInfo {
        var minted: SessionInfo? = null

        val info = synchronized(this) {
            // Clocks are read INSIDE the lock: a caller preempted between an
            // outside read and the lock would write its stale timestamp over a
            // newer one, silently shrinking the inactivity window by the
            // preemption delay. (The iOS actor gets this for free.)
            val wallNow = wallClockMillis()
            val monoNow = monotonicClockMillis()
            val currentId = sessionId
            if (currentId != null) {
                if (monoNow - lastActivityMono > timeoutMillis) {
                    mintLocked(wallNow, monoNow).also { minted = it }
                } else {
                    lastActivityMono = monoNow
                    if (monoNow - lastPersistedMono >= PERSIST_THROTTLE_MILLIS) {
                        storage.setLastActivityMs(wallNow)
                        lastPersistedMono = monoNow
                    }
                    SessionInfo(currentId, sessionCount, startedNewSession = false)
                }
            } else {
                // First touch of this process: resume the persisted session
                // when the restart happened inside the inactivity window.
                val storedId = storage.getSessionId()
                val storedLast = storage.getLastActivityMs()
                if (storedId != null && storedLast != null &&
                    storedLast <= wallNow && wallNow - storedLast <= timeoutMillis
                ) {
                    sessionId = storedId
                    sessionCount = storage.getSessionCount() ?: 1
                    lastActivityMono = monoNow
                    // Once per process — no throttle, and skipping it would
                    // leave the pre-restart timestamp in place for up to a
                    // throttle window.
                    storage.setLastActivityMs(wallNow)
                    lastPersistedMono = monoNow
                    SessionInfo(storedId, sessionCount, startedNewSession = false)
                } else {
                    mintLocked(wallNow, monoNow).also { minted = it }
                }
            }
        }

        // Fired outside the lock — the handler tracks an event whose
        // enrichment re-enters touch(); it sees the session minted above with
        // startedNewSession = false, so the relay cannot loop.
        minted?.let { onSessionStart.fire(it) }
        return info
    }

    /**
     * The current session without recording activity — a read must not extend
     * the inactivity window, or a diagnostics poller could keep a session
     * alive forever. Returns null before the first [touch] of the process;
     * stale-past-timeout is acceptable for a diagnostic read.
     */
    fun peek(): SessionInfo? = synchronized(this) {
        sessionId?.let { SessionInfo(it, sessionCount, startedNewSession = false) }
    }

    private fun mintLocked(wallNow: Long, monoNow: Long): SessionInfo {
        // Continue the persisted lifetime counter even when the session itself
        // expired — the ordinal is per install, not per session chain.
        val previousCount = if (sessionCount > 0) sessionCount else storage.getSessionCount() ?: 0
        val newId = wallNow.toString()
        sessionId = newId
        sessionCount = previousCount + 1
        lastActivityMono = monoNow
        storage.setSession(id = newId, count = sessionCount, lastActivityMs = wallNow)
        lastPersistedMono = monoNow
        return SessionInfo(newId, sessionCount, startedNewSession = true)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 30

        /**
         * The persisted last-activity only matters across a process restart,
         * where its precision competes with a timeout of at least one minute —
         * so a write per event is pure SharedPreferences churn under a chatty
         * producer (webview bridge, rapid screen tracking). Throttled to one
         * write per window; under a monotone wall clock, a crash loses at most
         * this much of the inactivity window. A backward wall jump inside one
         * window followed by process death can instead overstate the persisted
         * activity by the jump size — healed at the next persist, and able to
         * resurrect a dead session only if the jump exceeds the timeout itself.
         */
        const val PERSIST_THROTTLE_MILLIS = 5_000L
    }
}
