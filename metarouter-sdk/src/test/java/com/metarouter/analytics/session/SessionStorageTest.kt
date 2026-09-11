package com.metarouter.analytics.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.storage.IdentityStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionStorageTest {

    private lateinit var context: Context
    private lateinit var storage: SessionStorage
    private lateinit var identityStorage: IdentityStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = SessionStorage(context)
        identityStorage = IdentityStorage(context)
        storage.clear()
        identityStorage.clear()
    }

    @After
    fun tearDown() {
        storage.clear()
        identityStorage.clear()
    }

    @Test
    fun `get returns null when not set`() {
        assertNull(storage.getSessionId())
        assertNull(storage.getSessionCount())
        assertNull(storage.getLastActivityMs())
    }

    @Test
    fun `setSession persists all three fields`() {
        storage.setSession(id = "1757400000000", count = 3, lastActivityMs = 1_757_400_123_456L)

        assertEquals("1757400000000", storage.getSessionId())
        assertEquals(3, storage.getSessionCount())
        assertEquals(1_757_400_123_456L, storage.getLastActivityMs())
    }

    @Test
    fun `setLastActivityMs updates only last activity`() {
        storage.setSession(id = "1757400000000", count = 2, lastActivityMs = 1_000L)

        storage.setLastActivityMs(2_000L)

        assertEquals("1757400000000", storage.getSessionId())
        assertEquals(2, storage.getSessionCount())
        assertEquals(2_000L, storage.getLastActivityMs())
    }

    @Test
    fun `data persists across instances`() {
        storage.setSession(id = "1757400000000", count = 1, lastActivityMs = 1_000L)

        val storage2 = SessionStorage(context)
        assertEquals("1757400000000", storage2.getSessionId())
        assertEquals(1, storage2.getSessionCount())
        assertEquals(1_000L, storage2.getLastActivityMs())
    }

    /**
     * Session storage lives in its own preferences file that [IdentityStorage.clear]
     * never enumerates. This is the structural guarantee that `reset()` cannot end a
     * session as a side effect of clearing identity — a logout mid-session must not
     * fragment the analytics session.
     */
    @Test
    fun `session storage is independent of identity storage clear`() {
        storage.setSession(id = "1757400000000", count = 5, lastActivityMs = 9_000L)
        identityStorage.set(IdentityStorage.KEY_ANONYMOUS_ID, "anon-id")
        identityStorage.set(IdentityStorage.KEY_USER_ID, "user-id")

        identityStorage.clear()

        assertNull(identityStorage.get(IdentityStorage.KEY_ANONYMOUS_ID))
        assertNull(identityStorage.get(IdentityStorage.KEY_USER_ID))
        assertEquals("1757400000000", storage.getSessionId())
        assertEquals(5, storage.getSessionCount())
        assertEquals(9_000L, storage.getLastActivityMs())
    }

    /**
     * A stored count of 0 must read back as 0, not as "absent" — the reader uses
     * `contains`, not a sentinel default, precisely for this.
     */
    @Test
    fun `stored zero count is not absent`() {
        storage.setSession(id = "1", count = 0, lastActivityMs = 1L)
        assertEquals(0, storage.getSessionCount())
    }

    @Test
    fun `keys match cross-platform contract`() {
        assertEquals("metarouter:session:id", SessionStorage.KEY_SESSION_ID)
        assertEquals("metarouter:session:count", SessionStorage.KEY_SESSION_COUNT)
        assertEquals("metarouter:session:last_activity_ms", SessionStorage.KEY_LAST_ACTIVITY_MS)
    }

    @Test
    fun `clear removes all fields`() {
        storage.setSession(id = "1757400000000", count = 1, lastActivityMs = 1_000L)

        storage.clear()

        assertNull(storage.getSessionId())
        assertNull(storage.getSessionCount())
        assertNull(storage.getLastActivityMs())
    }
}
