package com.metarouter.analytics

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.storage.IdentityStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * initialize(invalid) then initialize(valid): the refusal is superseded, so
 * getAnonymousId must answer from the valid session, never "" (AC4, sc-42966).
 */
@RunWith(RobolectricTestRunner::class)
class RefusedThenValidAnonymousIdTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val releaseContext: Context = object : ContextWrapper(context) {
        override fun getApplicationInfo(): ApplicationInfo =
            ApplicationInfo(context.applicationInfo).apply { flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
    }

    private fun options(writeKey: String) = InitOptions(
        writeKey = writeKey,
        ingestionHost = "https://events.example.com",
        flushIntervalSeconds = 999,
        trackLifecycleEvents = false
    )

    @Before
    fun clearStorage() {
        IdentityStorage(context).clear()
        SessionStorage(context).clear()
        MetaRouter.hostIdentityForTesting.replaceStorageForTesting(IdentityStorage(context))
    }

    @After
    fun teardown() = runBlocking { MetaRouter.resetForTesting() }

    private class Tally { var empty = 0; var hung = 0; var ok = 0 }

    private fun run(name: String, rounds: Int, round: suspend CoroutineScope.(Int) -> String?): Tally = runBlocking {
        val t = Tally()
        for (i in 0 until rounds) {
            when (val id = round(i)) {
                null -> t.hung++
                "" -> t.empty++
                else -> t.ok++
            }
            MetaRouter.resetForTesting()
        }
        println("RESULT $name ok=${t.ok} empty=${t.empty} hung=${t.hung} of $rounds")
        t
    }

    @Test
    fun `getAnonymousId called after both initializes never returns empty`() {
        val t = run("after-both", 200) { i ->
            MetaRouter.Analytics.initialize(releaseContext, options(""))
            if (i % 3 == 1) delay(1)
            MetaRouter.Analytics.initialize(context, options("wk-a111"))
            if (i % 3 == 2) delay(1)
            withTimeoutOrNull(3000) { MetaRouter.Analytics.shared.getAnonymousId() }
        }
        assertEquals("empty ids", 0, t.empty)
        assertEquals("hung", 0, t.hung)
    }

    // Informational: a read started before initialize(valid) is called may
    // legitimately see the refusal (iOS's settled() also waits only for lifecycle
    // work already called), so "" is not asserted against here; hangs are.
    @Test
    fun `getAnonymousId started between the initializes never hangs`() {
        val t = run("between", 200) { i ->
            MetaRouter.Analytics.initialize(releaseContext, options(""))
            val waiter = async(Dispatchers.Default) { MetaRouter.Analytics.shared.getAnonymousId() }
            if (i % 2 == 1) delay(1)
            MetaRouter.Analytics.initialize(context, options("wk-a111"))
            withTimeoutOrNull(3000) { waiter.await() }.also { waiter.cancel() }
        }
        assertEquals("hung", 0, t.hung)
    }
}
