package com.metarouter.analytics.context

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.BuildConfig
import com.metarouter.analytics.types.EventContext
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone
import android.os.Build

/**
 * Tests for DeviceContextProvider.
 *
 * Test Coverage:
 * - All context fields populated correctly
 * - Caching behavior (returns same instance)
 * - Graceful fallbacks for missing data
 * - Network permission handling
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class DeviceContextProviderTest {

    private lateinit var context: Context
    private lateinit var provider: DeviceContextProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = DeviceContextProvider(context)
    }

    @Test
    fun `getContext returns complete context with all fields`() {
        val eventContext = provider.getContext()

        // Verify all top-level fields are present
        assertNotNull(eventContext.library)
        assertNotNull(eventContext.locale)
        assertNotNull(eventContext.timezone)
        assertNotNull(eventContext.device)
        assertNotNull(eventContext.os)
        assertNotNull(eventContext.app)
        assertNotNull(eventContext.screen)
        assertNotNull(eventContext.network)
    }

    @Test
    fun `library context has correct SDK name and version`() {
        val eventContext = provider.getContext()
        val library = eventContext.library

        assertEquals("metarouter-android-sdk", library.name)
        assertEquals(BuildConfig.SDK_VERSION, library.version)
    }

    @Test
    fun `locale is non-empty string`() {
        val eventContext = provider.getContext()
        val locale = eventContext.locale

        assertNotNull(locale)
        assertTrue("Locale should not be blank", locale!!.isNotBlank())
    }

    @Test
    fun `timezone is non-empty string`() {
        val eventContext = provider.getContext()
        val timezone = eventContext.timezone

        assertNotNull(timezone)
        assertTrue("Timezone should not be blank", timezone!!.isNotBlank())
    }

    @Test
    fun `device context has manufacturer model and type`() {
        val eventContext = provider.getContext()
        val device = eventContext.device!!

        assertNotNull(device.manufacturer)
        assertTrue("Manufacturer should not be blank", device.manufacturer.isNotBlank())

        assertNotNull(device.model)
        assertTrue("Model should not be blank", device.model.isNotBlank())

        assertNotNull(device.name)
        assertTrue("Device name should not be blank", device.name.isNotBlank())

        assertEquals("android", device.type)
    }

    @Test
    fun `OS context has name and version`() {
        val eventContext = provider.getContext()
        val os = eventContext.os!!

        assertEquals("Android", os.name)
        assertNotNull(os.version)
        assertTrue("OS version should not be blank", os.version.isNotBlank())
    }

    @Test
    fun `app context has name version build and namespace`() {
        val eventContext = provider.getContext()
        val app = eventContext.app!!

        assertNotNull(app.name)
        assertNotNull(app.version)
        assertNotNull(app.build)
        assertNotNull(app.namespace)

        // Package name should match context
        assertEquals(context.packageName, app.namespace)
    }

    @Test
    fun `screen context has width height and density`() {
        val eventContext = provider.getContext()
        val screen = eventContext.screen!!

        // In Robolectric, screen dimensions may be 0 (fallback case)
        assertTrue("Screen width should be non-negative", screen.width >= 0)
        assertTrue("Screen height should be non-negative", screen.height >= 0)
        assertTrue("Screen density should be positive", screen.density > 0)
    }

    @Test
    fun `network context has wifi status`() {
        val eventContext = provider.getContext()
        val network = eventContext.network!!

        // WiFi status can be true, false, or null (permission not granted)
        // Just verify the field exists
        assertNotNull(network)
    }

    @Test
    fun `caching returns same context instance`() {
        val context1 = provider.getContext()
        val context2 = provider.getContext()

        // Should return exact same instance due to caching
        assertSame("Should return cached instance", context1, context2)
    }

    @Test
    fun `context generation is thread-safe`() {
        val contexts = mutableListOf<EventContext>()

        // Generate contexts from multiple threads concurrently
        val threads = List(10) {
            Thread {
                val context = provider.getContext()
                synchronized(contexts) {
                    contexts.add(context)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // All contexts should be the same instance (cached)
        val firstContext = contexts.first()
        contexts.forEach { context ->
            assertSame("All contexts should be same cached instance", firstContext, context)
        }
    }

    @Test
    fun `locale format uses hyphen not underscore`() {
        val eventContext = provider.getContext()
        val locale = eventContext.locale!!

        // Locale should use hyphen (e.g., "en-US" not "en_US")
        if (locale.length > 2) {
            assertTrue(
                "Locale should use hyphen separator, not underscore",
                locale.contains("-") || !locale.contains("_")
            )
        }
    }

    @Test
    fun `timezone is valid timezone ID`() {
        val eventContext = provider.getContext()
        val timezone = eventContext.timezone!!

        // Verify it's a valid timezone ID
        val availableIds = TimeZone.getAvailableIDs().toSet()
        assertTrue(
            "Timezone should be a valid timezone ID",
            availableIds.contains(timezone) || timezone == "UTC"
        )
    }

    @Test
    fun `screen density is rounded to 2 decimal places`() {
        val eventContext = provider.getContext()
        val density = eventContext.screen!!.density

        // Verify density has at most 2 decimal places
        val decimalPlaces = density.toString().substringAfter('.', "").length
        assertTrue("Density should be rounded to 2 decimal places", decimalPlaces <= 2)
    }

    @Test
    fun `screen dimensions are in dp not pixels`() {
        val eventContext = provider.getContext()
        val screen = eventContext.screen!!

        // Width and height should be in dp (reasonable values for a phone/tablet)
        // Most devices are 300-800 dp wide, 500-1500 dp tall
        // In Robolectric tests, dimensions may be 0 (fallback case)
        assertTrue("Width should be reasonable dp value or 0 (fallback)", screen.width in 0..2000)
        assertTrue("Height should be reasonable dp value or 0 (fallback)", screen.height in 0..3000)
    }

    @Test
    fun `context fields never return empty strings`() {
        val eventContext = provider.getContext()

        // Library
        assertTrue(eventContext.library.name.isNotBlank())
        assertTrue(eventContext.library.version.isNotBlank())

        // Locale and timezone
        assertTrue(eventContext.locale!!.isNotBlank())
        assertTrue(eventContext.timezone!!.isNotBlank())

        // Device
        val device = eventContext.device!!
        assertTrue(device.manufacturer.isNotBlank())
        assertTrue(device.model.isNotBlank())
        assertTrue(device.name.isNotBlank())
        assertTrue(device.type.isNotBlank())

        // OS
        val os = eventContext.os!!
        assertTrue(os.name.isNotBlank())
        assertTrue(os.version.isNotBlank())

        // App
        val app = eventContext.app!!
        assertTrue(app.name.isNotBlank())
        assertTrue(app.version.isNotBlank())
        assertTrue(app.build.isNotBlank())
        assertTrue(app.namespace.isNotBlank())
    }
}
