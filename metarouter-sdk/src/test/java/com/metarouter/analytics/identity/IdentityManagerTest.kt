package com.metarouter.analytics.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.storage.IdentityStorage
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_ANONYMOUS_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_GROUP_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_USER_ID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityManagerTest {

    private lateinit var manager: IdentityManager
    private lateinit var storage: IdentityStorage
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = IdentityStorage(context)
        storage.clear()
        manager = IdentityManager(storage)
    }

    @After
    fun teardown() = runTest {
        manager.reset()
    }

    // Anonymous ID

    @Test
    fun `getAnonymousId generates new ID when not set`() = runTest {
        val id = manager.getAnonymousId()
        assertNotNull(id)
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `getAnonymousId returns same ID on multiple calls`() = runTest {
        val id1 = manager.getAnonymousId()
        val id2 = manager.getAnonymousId()
        assertEquals(id1, id2)
    }

    @Test
    fun `getAnonymousId generates UUID format`() = runTest {
        val id = manager.getAnonymousId()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue("Expected UUID format, got: $id", id.matches(uuidRegex))
    }

    @Test
    fun `getAnonymousId persists across instances`() = runTest {
        val id1 = IdentityManager(context).getAnonymousId()
        val id2 = IdentityManager(context).getAnonymousId()
        assertEquals(id1, id2)
    }

    @Test
    fun `getAnonymousId loads from storage if present`() = runTest {
        storage.set(KEY_ANONYMOUS_ID, "stored-anonymous-id")
        val id = IdentityManager(context).getAnonymousId()
        assertEquals("stored-anonymous-id", id)
    }

    // User ID

    @Test
    fun `getUserId returns null when not set`() = runTest {
        assertNull(manager.getUserId())
    }

    @Test
    fun `setUserId stores and retrieves value`() = runTest {
        assertTrue(manager.setUserId("user-123"))
        assertEquals("user-123", manager.getUserId())
    }

    @Test
    fun `setUserId rejects empty string`() = runTest {
        assertFalse(manager.setUserId(""))
        assertNull(manager.getUserId())
    }

    @Test
    fun `setUserId rejects blank string`() = runTest {
        assertFalse(manager.setUserId("   "))
        assertNull(manager.getUserId())
    }

    @Test
    fun `setUserId overwrites previous value`() = runTest {
        manager.setUserId("user-1")
        manager.setUserId("user-2")
        assertEquals("user-2", manager.getUserId())
    }

    @Test
    fun `getUserId persists across instances`() = runTest {
        IdentityManager(context).setUserId("user-persistent")
        assertEquals("user-persistent", IdentityManager(context).getUserId())
    }

    // Group ID

    @Test
    fun `getGroupId returns null when not set`() = runTest {
        assertNull(manager.getGroupId())
    }

    @Test
    fun `setGroupId stores and retrieves value`() = runTest {
        assertTrue(manager.setGroupId("company-456"))
        assertEquals("company-456", manager.getGroupId())
    }

    @Test
    fun `setGroupId rejects empty string`() = runTest {
        assertFalse(manager.setGroupId(""))
        assertNull(manager.getGroupId())
    }

    @Test
    fun `setGroupId rejects blank string`() = runTest {
        assertFalse(manager.setGroupId("   "))
        assertNull(manager.getGroupId())
    }

    @Test
    fun `getGroupId persists across instances`() = runTest {
        IdentityManager(context).setGroupId("company-persistent")
        assertEquals("company-persistent", IdentityManager(context).getGroupId())
    }

    // Reset

    @Test
    fun `reset clears all identity data`() = runTest {
        manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("company-456")

        manager.reset()

        assertNull(storage.get(KEY_ANONYMOUS_ID))
        assertNull(storage.get(KEY_USER_ID))
        assertNull(storage.get(KEY_GROUP_ID))
    }

    @Test
    fun `reset generates new anonymous ID on next call`() = runTest {
        val id1 = manager.getAnonymousId()
        manager.reset()
        val id2 = manager.getAnonymousId()
        assertNotEquals(id1, id2)
    }

    // Edge cases

    @Test
    fun `handles special characters in IDs`() = runTest {
        val specialId = "user-123!@#\$%^&*()_+-={}[]|:;<>?,."
        manager.setUserId(specialId)
        assertEquals(specialId, manager.getUserId())
    }

    @Test
    fun `handles unicode characters in IDs`() = runTest {
        val unicodeId = "用户-123-ユーザー"
        manager.setUserId(unicodeId)
        assertEquals(unicodeId, manager.getUserId())
    }

    @Test
    fun `all identity types can coexist`() = runTest {
        val anonymousId = manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("company-456")

        assertEquals(anonymousId, manager.getAnonymousId())
        assertEquals("user-123", manager.getUserId())
        assertEquals("company-456", manager.getGroupId())
    }
}
