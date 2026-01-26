package com.metarouter.analytics.lifecycle

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLifecycleObserverTest {

    private lateinit var testScope: TestScope
    private lateinit var lifecycleOwner: LifecycleOwner

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        testScope = TestScope()
        lifecycleOwner = mockk()
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `onStart triggers foreground callback`() = runTest {
        var foregroundCalled = false
        var backgroundCalled = false

        val observer = AppLifecycleObserver(
            scope = testScope,
            onForeground = { foregroundCalled = true },
            onBackground = { backgroundCalled = true }
        )

        observer.onStart(lifecycleOwner)

        assertTrue("Foreground callback should be called", foregroundCalled)
        assertFalse("Background callback should not be called", backgroundCalled)
    }

    @Test
    fun `onStop triggers background callback`() = runTest {
        var foregroundCalled = false
        var backgroundCalled = false

        val observer = AppLifecycleObserver(
            scope = testScope,
            onForeground = { foregroundCalled = true },
            onBackground = { backgroundCalled = true }
        )

        observer.onStop(lifecycleOwner)
        testScope.testScheduler.advanceUntilIdle()

        assertFalse("Foreground callback should not be called", foregroundCalled)
        assertTrue("Background callback should be called", backgroundCalled)
    }

    @Test
    fun `multiple onStart calls are handled`() = runTest {
        var foregroundCount = 0

        val observer = AppLifecycleObserver(
            scope = testScope,
            onForeground = { foregroundCount++ },
            onBackground = { }
        )

        observer.onStart(lifecycleOwner)
        observer.onStart(lifecycleOwner)

        assertEquals("Foreground callback should be called twice", 2, foregroundCount)
    }

    @Test
    fun `multiple onStop calls are handled`() = runTest {
        var backgroundCount = 0

        val observer = AppLifecycleObserver(
            scope = testScope,
            onForeground = { },
            onBackground = { backgroundCount++ }
        )

        observer.onStop(lifecycleOwner)
        testScope.testScheduler.advanceUntilIdle()
        observer.onStop(lifecycleOwner)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals("Background callback should be called twice", 2, backgroundCount)
    }

    @Test
    fun `rapid foreground background transitions are handled`() = runTest {
        var foregroundCount = 0
        var backgroundCount = 0

        val observer = AppLifecycleObserver(
            scope = testScope,
            onForeground = { foregroundCount++ },
            onBackground = { backgroundCount++ }
        )

        observer.onStart(lifecycleOwner)
        observer.onStop(lifecycleOwner)
        testScope.testScheduler.advanceUntilIdle()
        observer.onStart(lifecycleOwner)
        observer.onStop(lifecycleOwner)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals("Foreground callback should be called twice", 2, foregroundCount)
        assertEquals("Background callback should be called twice", 2, backgroundCount)
    }
}
