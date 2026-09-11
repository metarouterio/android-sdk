package com.metarouter.analytics.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SessionManager logic tests. Both clocks are injected, so every scenario —
 * including "30 minutes pass" and "the user sets the device clock back" —
 * runs instantly and deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var storage: SessionStorage

    @Volatile private var wallNow = WALL_START
    @Volatile private var monoNow = MONO_START

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = SessionStorage(context)
        storage.clear()
        wallNow = WALL_START
        monoNow = MONO_START
    }

    @After
    fun tearDown() {
        storage.clear()
    }

    private fun makeManager(timeoutMinutes: Int = 30) = SessionManager(
        storage = storage,
        timeoutMinutes = timeoutMinutes,
        wallClockMillis = { wallNow },
        monotonicClockMillis = { monoNow }
    )

    private fun advance(minutes: Long) {
        wallNow += minutes * 60_000
        monoNow += minutes * 60_000
    }

    // ===== Minting =====

    @Test
    fun `cold start mints first session`() {
        val manager = makeManager()

        val info = manager.touch()

        assertEquals(WALL_START.toString(), info.sessionId)
        assertEquals(1, info.sessionCount)
        assertTrue(info.startedNewSession)
        assertEquals(WALL_START.toString(), storage.getSessionId())
        assertEquals(1, storage.getSessionCount())
        assertEquals(WALL_START, storage.getLastActivityMs())
    }

    @Test
    fun `activity within timeout extends session`() {
        val manager = makeManager()
        val first = manager.touch()

        advance(minutes = 29)
        val second = manager.touch()

        assertEquals(first.sessionId, second.sessionId)
        assertEquals(1, second.sessionCount)
        assertFalse(second.startedNewSession)
        assertEquals(wallNow, storage.getLastActivityMs())
    }

    /**
     * The window slides: repeated activity keeps one session alive far past the
     * timeout measured from its start — the from-start model would end an
     * actively used session mid-flight.
     */
    @Test
    fun `sliding window outlives timeout from session start`() {
        val manager = makeManager()
        val first = manager.touch()

        // 4 × 20 min of steady activity = 80 min since session start.
        var last = first
        repeat(4) {
            advance(minutes = 20)
            last = manager.touch()
        }

        assertEquals(first.sessionId, last.sessionId)
        assertFalse(last.startedNewSession)
    }

    @Test
    fun `inactivity past timeout rolls over`() {
        val manager = makeManager()
        val first = manager.touch()

        advance(minutes = 31)
        val second = manager.touch()

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(wallNow.toString(), second.sessionId)
        assertEquals(2, second.sessionCount)
        assertTrue(second.startedNewSession)
    }

    /**
     * Rollover uses `>`: exactly `timeout` of inactivity is still the same
     * session. Web and iOS behave identically at this boundary; a flipped
     * `>`/`>=` would silently fork the platforms one millisecond apart.
     */
    @Test
    fun `exactly at timeout is still same session`() {
        val manager = makeManager()
        val first = manager.touch()
        val timeoutMs = 30 * 60_000L

        wallNow += timeoutMs
        monoNow += timeoutMs
        val atBoundary = manager.touch()
        assertEquals("elapsed == timeout keeps the session", first.sessionId, atBoundary.sessionId)

        // The boundary touch was itself activity, so measure the next full
        // window from it.
        wallNow += timeoutMs + 1
        monoNow += timeoutMs + 1
        val pastBoundary = manager.touch()
        assertNotEquals("elapsed == timeout + 1ms rolls over", first.sessionId, pastBoundary.sessionId)
    }

    // ===== In-process clock authority =====

    /**
     * In-process inactivity is measured on the monotonic clock only: a
     * wall-clock jump (NTP, user changes the time) during a live session must
     * neither end it nor extend it.
     */
    @Test
    fun `wall clock jump does not affect live session`() {
        val manager = makeManager()
        val first = manager.touch()

        // Wall leaps forward 6 hours; only 5 real minutes elapse.
        wallNow += 360 * 60_000L
        monoNow += 5 * 60_000L
        val afterForwardJump = manager.touch()
        assertEquals(
            "forward wall jump must not end a session with no real inactivity",
            first.sessionId, afterForwardJump.sessionId
        )

        // Wall leaps back a day; 5 more real minutes elapse.
        wallNow -= 1_440 * 60_000L
        monoNow += 5 * 60_000L
        val afterBackwardJump = manager.touch()
        assertEquals(
            "backward wall jump must not end a session with no real inactivity",
            first.sessionId, afterBackwardJump.sessionId
        )
    }

    // ===== Restart resume =====

    @Test
    fun `restart within timeout resumes persisted session`() {
        storage.setSession(id = "1757400000000", count = 4, lastActivityMs = WALL_START)

        // New process: fresh manager, monotonic clock restarted near zero.
        wallNow = WALL_START + 10 * 60_000L
        monoNow = 1_000L
        val manager = makeManager()

        val info = manager.touch()

        assertEquals("1757400000000", info.sessionId)
        assertEquals(4, info.sessionCount)
        assertFalse(info.startedNewSession)
        assertEquals("resume refreshes last activity", wallNow, storage.getLastActivityMs())
    }

    @Test
    fun `restart past timeout mints and continues counter`() {
        storage.setSession(id = "1757400000000", count = 4, lastActivityMs = WALL_START)

        wallNow = WALL_START + 31 * 60_000L
        monoNow = 1_000L
        val manager = makeManager()

        val info = manager.touch()

        assertNotEquals("1757400000000", info.sessionId)
        assertEquals("counter is per install, not per session chain", 5, info.sessionCount)
        assertTrue(info.startedNewSession)
    }

    /**
     * A persisted last-activity in the future means the device clock was set
     * back since it was written. Resuming would make the session immortal
     * (elapsed stays negative until the clock catches up), so it is treated as
     * expired — at most one extra session, self-healing on the next write.
     */
    @Test
    fun `restart with future last activity mints new session`() {
        storage.setSession(id = "1757500000000", count = 2, lastActivityMs = WALL_START + 60_000L)

        wallNow = WALL_START
        monoNow = 1_000L
        val manager = makeManager()

        val info = manager.touch()

        assertNotEquals("1757500000000", info.sessionId)
        assertEquals(3, info.sessionCount)
        assertTrue(info.startedNewSession)
    }

    @Test
    fun `restart with legacy partial state mints`() {
        // Id present but no last-activity (e.g. state written by a crashed
        // half-migration) must not resume a window of unknown age.
        storage.setSession(id = "1757400000000", count = 1, lastActivityMs = 1L)
        val prefs = context.getSharedPreferences("com.metarouter.analytics.session", Context.MODE_PRIVATE)
        prefs.edit().remove(SessionStorage.KEY_LAST_ACTIVITY_MS).commit()

        wallNow = WALL_START + 10 * 60_000L
        monoNow = 1_000L
        val manager = makeManager()

        val info = manager.touch()
        assertTrue(info.startedNewSession)
        assertEquals(2, info.sessionCount)
    }

    // ===== Persistence throttle =====

    /**
     * Persistence is throttled on the extend path: last-activity only matters
     * across a restart, so sub-window churn is skipped. The clocks advance
     * independently here — a throttle measured on wall time (the jump-prone
     * clock the monotonic choice exists to avoid) would persist early and fail
     * the first assertion.
     */
    @Test
    fun `extend persists at most once per throttle window`() {
        val manager = makeManager()
        manager.touch()
        val mintedAt = wallNow
        val window = SessionManager.PERSIST_THROTTLE_MILLIS

        // 1ms short of the window monotonically, while wall leaps far past it.
        wallNow += window * 10
        monoNow += window - 1
        manager.touch()
        assertEquals("sub-window extend must not hit storage", mintedAt, storage.getLastActivityMs())

        // One more millisecond of real time reaches the window — persists.
        wallNow += 1
        monoNow += 1
        manager.touch()
        assertEquals("extend at the throttle boundary persists", wallNow, storage.getLastActivityMs())
    }

    // ===== peek =====

    @Test
    fun `peek before first touch is null`() {
        val manager = makeManager()
        assertNull(manager.peek())
    }

    /**
     * A read is not activity: peek must not extend the window, or a
     * diagnostics poller could keep a session alive forever.
     */
    @Test
    fun `peek does not extend session`() {
        val manager = makeManager()
        val first = manager.touch()

        advance(minutes = 29)
        val peeked = manager.peek()
        assertEquals(first.sessionId, peeked?.sessionId)
        assertEquals("peek must not persist activity", WALL_START, storage.getLastActivityMs())

        // 2 more minutes = 31 since the only touch; if peek had extended,
        // this would still be the first session.
        advance(minutes = 2)
        val second = manager.touch()
        assertNotEquals(first.sessionId, second.sessionId)
    }

    // ===== Concurrency =====

    /**
     * A burst of concurrent events at cold start must mint exactly one
     * session: the lock serializes touch(), so exactly one caller sees
     * startedNewSession and everyone agrees on the id.
     */
    @Test
    fun `concurrent cold start mints exactly one session`() {
        val manager = makeManager()
        val threads = 32
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<SessionInfo>()
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                results.add(manager.touch())
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        val ids = results.map { it.sessionId }.toSet()
        assertEquals("every concurrent touch sees the same session", 1, ids.size)
        assertEquals("exactly one mint", 1, results.count { it.startedNewSession })
        assertTrue(results.all { it.sessionCount == 1 })
    }

    companion object {
        private const val WALL_START = 1_757_400_000_000L
        private const val MONO_START = 50_000L
    }
}
