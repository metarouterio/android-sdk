package com.metarouter.analytics.lifecycle

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Coordinator is a thin pass-through to the tracker; the full algorithm is exercised
 * by `LifecycleEventTrackerTest`. These tests confirm the seam exists and forwards
 * each call exactly once with the same arguments.
 */
class LifecycleCoordinatorTest {

    @Test
    fun `onForeground forwards to tracker`() {
        val tracker = mockk<LifecycleEventTracker>(relaxed = true)
        LifecycleCoordinator(tracker).onForeground()
        verify(exactly = 1) { tracker.onForeground() }
    }

    @Test
    fun `onBackground forwards to tracker`() {
        val tracker = mockk<LifecycleEventTracker>(relaxed = true)
        LifecycleCoordinator(tracker).onBackground()
        verify(exactly = 1) { tracker.onBackground() }
    }

    @Test
    fun `onReady forwards to tracker onSdkReady`() {
        val tracker = mockk<LifecycleEventTracker>(relaxed = true)
        LifecycleCoordinator(tracker).onReady()
        verify(exactly = 1) { tracker.onSdkReady() }
    }

    @Test
    fun `openURL forwards uri and source application to tracker`() {
        val tracker = mockk<LifecycleEventTracker>(relaxed = true)
        val uri = mockk<Uri>()
        every { uri.toString() } returns "https://example.com"

        LifecycleCoordinator(tracker).openURL(uri, "com.referrer")

        verify(exactly = 1) { tracker.openURL(uri, "com.referrer") }
    }

    @Test
    fun `openURL forwards null source application unchanged`() {
        val tracker = mockk<LifecycleEventTracker>(relaxed = true)
        val uri = mockk<Uri>()
        every { uri.toString() } returns "https://example.com"

        LifecycleCoordinator(tracker).openURL(uri, null)

        verify(exactly = 1) { tracker.openURL(uri, null) }
    }
}
