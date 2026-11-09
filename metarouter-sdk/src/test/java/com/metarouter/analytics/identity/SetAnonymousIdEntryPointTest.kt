package com.metarouter.analytics.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.MetaRouter
import com.metarouter.analytics.storage.IdentityStorage
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_ANONYMOUS_ID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * reset() then setAnonymousId through the static entry points the README points hosts
 * to. Each reset is queued behind an initialize on MetaRouter's single-threaded init
 * dispatcher, so the set always lands between the reset's note and its clear — the
 * window the note exists for.
 */
@RunWith(RobolectricTestRunner::class)
class SetAnonymousIdEntryPointTest {

    private lateinit var context: Context
    private lateinit var storage: IdentityStorage

    private val options = InitOptions(
        writeKey = "wk",
        ingestionHost = "https://events.example.com",
        flushIntervalSeconds = 999,
        trackLifecycleEvents = false
    )

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        storage = IdentityStorage(context).apply { clear() }
        MetaRouter.resetForTesting()
        MetaRouter.hostIdentityForTesting.replaceStorageForTesting(null)
    }

    @After
    fun tearDown() = runBlocking {
        MetaRouter.resetForTesting()
        MetaRouter.hostIdentityForTesting.replaceStorageForTesting(null)
        storage.clear()
    }

    @Test
    fun `MetaRouter Analytics reset then setAnonymousId keeps the host id`() = runBlocking<Unit> {
        MetaRouter.Analytics.initialize(context, options)
        MetaRouter.Analytics.reset()
        assertTrue(MetaRouter.Analytics.shared.setAnonymousId("web-anon-2"))

        // FIFO: this init runs after the reset, so once it returns the clear has run.
        MetaRouter.Analytics.initializeAndWait(context, options)

        assertEquals("web-anon-2", storage.get(KEY_ANONYMOUS_ID))
        assertEquals("web-anon-2", MetaRouter.Analytics.shared.getAnonymousId())
    }

    @Test
    fun `MetaRouter Analytics resetAndWait then setAnonymousId keeps the host id`() = runBlocking<Unit> {
        MetaRouter.Analytics.initialize(context, options)
        // Undispatched: runs up to its first suspension, so the note is taken here.
        val reset = launch(start = CoroutineStart.UNDISPATCHED) { MetaRouter.Analytics.resetAndWait() }
        assertTrue(MetaRouter.Analytics.shared.setAnonymousId("web-anon-2"))
        reset.join()

        assertEquals("web-anon-2", storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `setAnonymousId then MetaRouter Analytics reset discards the host id`() = runBlocking<Unit> {
        MetaRouter.Analytics.initializeAndWait(context, options)
        assertTrue(MetaRouter.Analytics.shared.setAnonymousId("web-anon-1"))
        MetaRouter.Analytics.reset()

        MetaRouter.Analytics.initializeAndWait(context, options)

        assertTrue(storage.get(KEY_ANONYMOUS_ID) != "web-anon-1")
    }
}
