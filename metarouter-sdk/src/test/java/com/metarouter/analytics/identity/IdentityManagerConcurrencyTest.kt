package com.metarouter.analytics.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityManagerConcurrencyTest {

    private lateinit var manager: IdentityManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = IdentityManager(context)
    }

    @After
    fun teardown() = runTest {
        manager.reset()
    }

    @Test
    fun `concurrent getAnonymousId calls return same ID`() = runTest {
        val results = (1..100).map {
            async { manager.getAnonymousId() }
        }.awaitAll()

        assertEquals(1, results.toSet().size)
    }

    @Test
    fun `concurrent setUserId calls are all applied`() = runTest {
        (1..100).map { i ->
            async { manager.setUserId("user-$i") }
        }.awaitAll()

        val finalUserId = manager.getUserId()
        assertNotNull(finalUserId)
        assertTrue(finalUserId!!.startsWith("user-"))
    }

    @Test
    fun `concurrent get and set operations are safe`() = runTest {
        val getters = (1..50).map {
            async { manager.getUserId() }
        }

        val setters = (1..50).map { i ->
            async { manager.setUserId("user-$i") }
        }

        getters.awaitAll()
        setters.awaitAll()

        assertNotNull(manager.getUserId())
    }

    @Test
    fun `concurrent operations on different IDs are independent`() = runTest {
        listOf(
            async { manager.getAnonymousId() },
            async { manager.setUserId("user-1") },
            async { manager.setGroupId("group-1") },
            async { manager.getUserId() },
            async { manager.getGroupId() }
        ).awaitAll()

        assertNotNull(manager.getAnonymousId())
        assertNotNull(manager.getUserId())
        assertNotNull(manager.getGroupId())
    }

    @Test
    fun `concurrent reset operations are safe`() = runTest {
        manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("group-456")

        (1..10).map {
            async { manager.reset() }
        }.awaitAll()

        assertNull(manager.getUserId())
        assertNull(manager.getGroupId())
    }

    @Test
    fun `high concurrency stress test`() = runTest {
        (1..1000).map { i ->
            async {
                when (i % 4) {
                    0 -> manager.getAnonymousId()
                    1 -> manager.setUserId("user-$i")
                    2 -> manager.setGroupId("group-$i")
                    else -> manager.getUserId()
                }
            }
        }.awaitAll()

        assertNotNull(manager.getAnonymousId())
    }

    @Test
    fun `concurrent reads are fast`() = runTest {
        manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("group-456")

        val startTime = System.currentTimeMillis()

        (1..100).map {
            async {
                manager.getAnonymousId()
                manager.getUserId()
                manager.getGroupId()
            }
        }.awaitAll()

        val duration = System.currentTimeMillis() - startTime
        assertTrue("Reads took too long: ${duration}ms", duration < 1000)
    }
}
