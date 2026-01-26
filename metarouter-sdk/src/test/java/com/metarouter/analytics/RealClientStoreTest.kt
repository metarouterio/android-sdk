package com.metarouter.analytics

import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RealClientStoreTest {

    private lateinit var store: RealClientStore
    private lateinit var mockClient: MetaRouterAnalyticsClient

    @Before
    fun setup() {
        store = RealClientStore()
        mockClient = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ===== Basic Operations =====

    @Test
    fun `stores client on first set and returns true`() = runTest {
        val result = store.set(mockClient)

        assertTrue(result)
        assertEquals(mockClient, store.get())
        assertTrue(store.hasClient())
    }

    @Test
    fun `rejects second set call and returns false`() = runTest {
        val secondClient: MetaRouterAnalyticsClient = mockk(relaxed = true)

        val firstResult = store.set(mockClient)
        val secondResult = store.set(secondClient)

        assertTrue(firstResult)
        assertFalse(secondResult)
        // Original client should still be stored
        assertEquals(mockClient, store.get())
    }

    @Test
    fun `get returns null when no client stored`() = runTest {
        val result = store.get()

        assertNull(result)
        assertFalse(store.hasClient())
    }

    @Test
    fun `clear allows new client to be set`() = runTest {
        val secondClient: MetaRouterAnalyticsClient = mockk(relaxed = true)

        store.set(mockClient)
        store.clear()

        assertFalse(store.hasClient())
        assertNull(store.get())

        // Should now accept a new client
        val result = store.set(secondClient)
        assertTrue(result)
        assertEquals(secondClient, store.get())
    }

    @Test
    fun `hasClient returns false after clear`() = runTest {
        store.set(mockClient)
        assertTrue(store.hasClient())

        store.clear()
        assertFalse(store.hasClient())
    }

    // ===== Concurrency =====

    @Test
    fun `concurrent set operations - exactly one succeeds`() = runTest {
        val clients = (1..10).map { mockk<MetaRouterAnalyticsClient>(relaxed = true) }

        val results = clients.map { client ->
            async { store.set(client) }
        }.awaitAll()

        // Exactly one should succeed
        val successCount = results.count { it }
        assertEquals(1, successCount)

        // Store should have a client
        assertTrue(store.hasClient())
        assertNotNull(store.get())
    }

    @Test
    fun `concurrent get operations are safe`() = runTest {
        store.set(mockClient)

        val results = (1..100).map {
            async { store.get() }
        }.awaitAll()

        // All should return the same client
        assertTrue(results.all { it == mockClient })
    }

    @Test
    fun `concurrent hasClient checks are safe`() = runTest {
        store.set(mockClient)

        val results = (1..100).map {
            async { store.hasClient() }
        }.awaitAll()

        // All should return true
        assertTrue(results.all { it })
    }

    @Test
    fun `concurrent set and get operations are safe`() = runTest {
        val setJobs = (1..50).map {
            async { store.set(mockk(relaxed = true)) }
        }

        val getJobs = (1..50).map {
            async { store.get() }
        }

        setJobs.awaitAll()
        getJobs.awaitAll()

        // Store should be in a consistent state
        assertTrue(store.hasClient())
    }
}
