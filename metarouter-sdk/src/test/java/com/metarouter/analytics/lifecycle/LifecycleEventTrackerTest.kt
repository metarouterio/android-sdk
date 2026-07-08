package com.metarouter.analytics.lifecycle

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.AnalyticsInterface
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.storage.LifecycleStorage
import com.metarouter.analytics.types.AppContext
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the cold-launch / foreground / background emission rules.
 * Uses a recording AnalyticsInterface and a real LifecycleStorage backed by Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class LifecycleEventTrackerTest {

    private data class TrackedEvent(val event: String, val properties: Map<String, Any?>?)

    private class RecordingAnalytics : AnalyticsInterface {
        val events = mutableListOf<TrackedEvent>()
        override fun track(event: String, properties: Map<String, Any?>?) {
            events.add(TrackedEvent(event, properties))
        }
        override fun identify(userId: String, traits: Map<String, Any?>?) {}
        override fun group(groupId: String, traits: Map<String, Any?>?) {}
        override fun screen(name: String, properties: Map<String, Any?>?) {}
        override fun page(name: String, properties: Map<String, Any?>?) {}
        override fun alias(newUserId: String) {}
        override fun setAdvertisingId(advertisingId: String) {}
        override fun clearAdvertisingId() {}
        override suspend fun flush() {}
        override suspend fun reset() {}
        override suspend fun getAnonymousId(): String = "anon"
        override fun enableDebugLogging() {}
        override suspend fun getDebugInfo(): Map<String, Any?> = emptyMap()
        override fun setTracing(enabled: Boolean) {}
    }

    private lateinit var analytics: RecordingAnalytics
    private lateinit var storage: LifecycleStorage
    private lateinit var identityManager: IdentityManager

    private val currentVersion = "1.4.0"
    private val currentBuild = "42"
    private val appContext = AppContext(
        name = "Test App",
        version = currentVersion,
        build = currentBuild,
        namespace = "com.example.test"
    )

    @Before
    fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        analytics = RecordingAnalytics()
        storage = LifecycleStorage(ctx)
        storage.clear()
        identityManager = mockk()
        every { identityManager.hasAnyValue() } returns false
    }

    private fun newTracker(
        foreground: Boolean = true
    ): LifecycleEventTracker = LifecycleEventTracker(
        analytics = analytics,
        storage = storage,
        appContext = appContext,
        identityManager = identityManager,
        foregroundStateProvider = { foreground }
    )

    // ===== Cold-launch: install / update detection =====

    @Test
    fun `cold launch with no stored version and no identity emits Installed then Opened`() {
        every { identityManager.hasAnyValue() } returns false

        newTracker().onSdkReady()

        assertEquals(2, analytics.events.size)
        assertEquals("Application Installed", analytics.events[0].event)
        assertEquals(currentVersion, analytics.events[0].properties?.get("version"))
        assertEquals(currentBuild, analytics.events[0].properties?.get("build"))

        assertEquals("Application Opened", analytics.events[1].event)
        assertEquals(false, analytics.events[1].properties?.get("from_background"))
        assertEquals(currentVersion, analytics.events[1].properties?.get("version"))
        assertEquals(currentBuild, analytics.events[1].properties?.get("build"))

        assertEquals(currentVersion, storage.getVersion())
        assertEquals(currentBuild, storage.getBuild())
    }

    @Test
    fun `cold launch with no stored version but identity present emits Updated unknown`() {
        every { identityManager.hasAnyValue() } returns true

        newTracker().onSdkReady()

        assertEquals(2, analytics.events.size)
        val updated = analytics.events[0]
        assertEquals("Application Updated", updated.event)
        assertEquals(currentVersion, updated.properties?.get("version"))
        assertEquals(currentBuild, updated.properties?.get("build"))
        assertEquals("unknown", updated.properties?.get("previous_version"))
        assertEquals("unknown", updated.properties?.get("previous_build"))

        assertEquals("Application Opened", analytics.events[1].event)
    }

    @Test
    fun `cold launch with matching stored version emits only Opened`() {
        storage.setVersionBuild(currentVersion, currentBuild)

        newTracker().onSdkReady()

        assertEquals(1, analytics.events.size)
        assertEquals("Application Opened", analytics.events[0].event)
        assertEquals(false, analytics.events[0].properties?.get("from_background"))
    }

    @Test
    fun `cold launch with different stored version emits Updated with previous values`() {
        storage.setVersionBuild("1.3.2", "37")

        newTracker().onSdkReady()

        assertEquals(2, analytics.events.size)
        val updated = analytics.events[0]
        assertEquals("Application Updated", updated.event)
        assertEquals(currentVersion, updated.properties?.get("version"))
        assertEquals(currentBuild, updated.properties?.get("build"))
        assertEquals("1.3.2", updated.properties?.get("previous_version"))
        assertEquals("37", updated.properties?.get("previous_build"))
    }

    @Test
    fun `cold launch with same version but different build counts as updated`() {
        storage.setVersionBuild(currentVersion, "37")

        newTracker().onSdkReady()

        assertEquals(2, analytics.events.size)
        assertEquals("Application Updated", analytics.events[0].event)
        assertEquals("37", analytics.events[0].properties?.get("previous_build"))
    }

    // ===== Foreground-state guards =====

    @Test
    fun `cold launch in non-foreground state defers Opened until first onForeground`() {
        val tracker = newTracker(foreground = false)
        tracker.onSdkReady()

        assertEquals(1, analytics.events.size)
        assertEquals("Application Installed", analytics.events[0].event)

        tracker.onForeground()

        assertEquals(2, analytics.events.size)
        val opened = analytics.events[1]
        assertEquals("Application Opened", opened.event)
        assertEquals(false, opened.properties?.get("from_background"))

        tracker.onForeground()
        assertEquals(3, analytics.events.size)
        assertEquals("Application Opened", analytics.events[2].event)
        assertEquals(true, analytics.events[2].properties?.get("from_background"))
    }

    @Test
    fun `first onForeground after cold-launch foreground emit is suppressed`() {
        val tracker = newTracker(foreground = true)
        tracker.onSdkReady()
        assertEquals(2, analytics.events.size)

        tracker.onForeground()

        assertEquals(2, analytics.events.size)
    }

    @Test
    fun `two background-foreground cycles emit two Opened from_background true`() {
        val tracker = newTracker(foreground = true)
        tracker.onSdkReady()
        tracker.onForeground() // suppressed
        assertEquals(2, analytics.events.size)

        tracker.onBackground()
        tracker.onForeground()
        tracker.onBackground()
        tracker.onForeground()

        val opened = analytics.events.filter { it.event == "Application Opened" }
        assertEquals(3, opened.size)
        assertEquals(false, opened[0].properties?.get("from_background"))
        assertEquals(true, opened[1].properties?.get("from_background"))
        assertEquals(true, opened[2].properties?.get("from_background"))
    }

    // ===== Background =====

    @Test
    fun `onBackground emits Application Backgrounded with empty properties`() {
        val tracker = newTracker()
        tracker.onSdkReady()
        analytics.events.clear()

        tracker.onBackground()

        assertEquals(1, analytics.events.size)
        assertEquals("Application Backgrounded", analytics.events[0].event)
        assertEquals(0, analytics.events[0].properties?.size ?: 0)
    }

    // ===== Deep-link buffer =====

    @Test
    fun `deep link before next Opened is included and buffer cleared after emit`() {
        val tracker = newTracker(foreground = true)

        tracker.openURL(mockUri("https://example.com/x"), "com.referrer.app")
        tracker.onSdkReady()

        val opened = analytics.events.first { it.event == "Application Opened" }
        assertEquals("https://example.com/x", opened.properties?.get("url"))
        assertEquals("com.referrer.app", opened.properties?.get("referring_application"))

        tracker.onForeground() // suppressed (cold-launch consumed)
        tracker.onBackground()
        tracker.onForeground()
        val laterOpened = analytics.events.last { it.event == "Application Opened" }
        assertNull(laterOpened.properties?.get("url"))
        assertNull(laterOpened.properties?.get("referring_application"))
    }

    @Test
    fun `deep link without source application omits referring_application`() {
        val tracker = newTracker(foreground = true)

        tracker.openURL(mockUri("https://example.com"), null)
        tracker.onSdkReady()

        val opened = analytics.events.first { it.event == "Application Opened" }
        assertEquals("https://example.com", opened.properties?.get("url"))
        assertFalse(opened.properties?.containsKey("referring_application") ?: false)
    }

    @Test
    fun `multiple openURL calls before Opened keep only the last URL`() {
        val tracker = newTracker(foreground = true)

        tracker.openURL(mockUri("https://example.com/first"), "com.first")
        tracker.openURL(mockUri("https://example.com/second"), "com.second")
        tracker.onSdkReady()

        val opened = analytics.events.first { it.event == "Application Opened" }
        assertEquals("https://example.com/second", opened.properties?.get("url"))
        assertEquals("com.second", opened.properties?.get("referring_application"))
    }

    // ===== Idempotency / ordering invariants =====

    @Test
    fun `onSdkReady is idempotent`() {
        val tracker = newTracker(foreground = true)
        tracker.onSdkReady()
        val firstCallCount = analytics.events.size

        tracker.onSdkReady()

        assertEquals(
            "second onSdkReady should be a no-op",
            firstCallCount,
            analytics.events.size
        )
        // suppressNextForeground armed by the first call should still consume the
        // imminent observer-driven onForeground — verify we don't double-emit.
        tracker.onForeground()
        assertEquals(firstCallCount, analytics.events.size)
    }

    @Test
    fun `onForeground before onSdkReady emits resume-style Opened (documents wiring contract)`() {
        // The host (LifecycleCoordinator in slice 3) is contractually required to
        // invoke onSdkReady before any onForeground. This test documents what happens
        // if that invariant breaks — wire-valid Opened with from_background=true, but
        // missing the install/update detection that should run first.
        val tracker = newTracker(foreground = true)

        tracker.onForeground()

        assertEquals(1, analytics.events.size)
        assertEquals("Application Opened", analytics.events[0].event)
        assertEquals(true, analytics.events[0].properties?.get("from_background"))
    }

    private fun mockUri(value: String): Uri {
        val uri = mockk<Uri>()
        every { uri.toString() } returns value
        return uri
    }
}
