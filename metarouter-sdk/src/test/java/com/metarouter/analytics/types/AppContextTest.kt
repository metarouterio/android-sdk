package com.metarouter.analytics.types

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke coverage for the `AppContext.fromContext` factory. The full `DeviceContextProvider`
 * test exercises this transitively, but this slice introduces the factory standalone and
 * deserves direct coverage.
 */
@RunWith(RobolectricTestRunner::class)
class AppContextTest {

    @Test
    fun `fromContext populates namespace from package`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val app = AppContext.fromContext(ctx)

        assertEquals(ctx.packageName, app.namespace)
    }

    @Test
    fun `fromContext returns non-blank fields`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val app = AppContext.fromContext(ctx)

        assertTrue("name should not be blank", app.name.isNotBlank())
        assertTrue("version should not be blank", app.version.isNotBlank())
        assertTrue("build should not be blank", app.build.isNotBlank())
        assertTrue("namespace should not be blank", app.namespace.isNotBlank())
    }

    @Test
    fun `fromContext is stable across calls`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val first = AppContext.fromContext(ctx)
        val second = AppContext.fromContext(ctx)

        assertEquals(first, second)
    }

    @Test
    fun `equality is structural`() {
        val a = AppContext("App", "1.0", "100", "com.example")
        val b = AppContext("App", "1.0", "100", "com.example")
        val c = AppContext("App", "1.1", "100", "com.example")

        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
