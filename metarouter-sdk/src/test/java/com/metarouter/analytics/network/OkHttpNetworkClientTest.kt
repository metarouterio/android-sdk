package com.metarouter.analytics.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.net.SocketTimeoutException

class OkHttpNetworkClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpNetworkClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpNetworkClient()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `posts JSON and returns 200 response`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"status":"ok"}""")
            .addHeader("Content-Type", "application/json"))

        val response = client.postJson(
            url = server.url("/events").toString(),
            body = """{"event":"test"}""".toByteArray(),
            timeoutMs = 5000
        )

        assertEquals(200, response.statusCode)
        assertEquals("""{"status":"ok"}""", response.body?.decodeToString())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"event":"test"}""", request.body.readUtf8())
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun `returns 4xx as valid response not exception`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(400)
            .setBody("""{"error":"bad request"}"""))

        val response = client.postJson(
            url = server.url("/events").toString(),
            body = "{}".toByteArray(),
            timeoutMs = 5000
        )

        assertEquals(400, response.statusCode)
        assertEquals("""{"error":"bad request"}""", response.body?.decodeToString())
    }

    @Test
    fun `returns 5xx as valid response not exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val response = client.postJson(
            url = server.url("/events").toString(),
            body = "{}".toByteArray(),
            timeoutMs = 5000
        )

        assertEquals(500, response.statusCode)
    }

    @Test
    fun `includes additional headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.postJson(
            url = server.url("/events").toString(),
            body = "{}".toByteArray(),
            timeoutMs = 5000,
            additionalHeaders = mapOf(
                "X-Custom-Header" to "custom-value",
                "Authorization" to "Bearer token123"
            )
        )

        val request = server.takeRequest()
        assertEquals("custom-value", request.getHeader("X-Custom-Header"))
        assertEquals("Bearer token123", request.getHeader("Authorization"))
    }

    @Test
    fun `captures response headers`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .addHeader("Retry-After", "60")
            .addHeader("X-RateLimit-Remaining", "0"))

        val response = client.postJson(
            url = server.url("/events").toString(),
            body = "{}".toByteArray(),
            timeoutMs = 5000
        )

        assertEquals(429, response.statusCode)
        assertEquals("60", response.headers["Retry-After"])
        assertEquals("0", response.headers["X-RateLimit-Remaining"])
    }

    @Test
    fun `handles empty response body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = client.postJson(
            url = server.url("/events").toString(),
            body = "{}".toByteArray(),
            timeoutMs = 5000
        )

        assertEquals(204, response.statusCode)
        assertTrue(response.body == null || response.body.isEmpty())
    }

    @Test(expected = IOException::class)
    fun `throws IOException on connection failure`() = runTest {
        // Shutdown server to simulate connection refused
        server.shutdown()

        client.postJson(
            url = "http://localhost:${server.port}/events",
            body = "{}".toByteArray(),
            timeoutMs = 1000
        )
    }

    @Test
    fun `respects timeout`() = runBlocking {
        // Create client with short timeout for this test
        val shortTimeoutClient = OkHttpNetworkClient(timeoutMs = 200L)

        // Use throttleBody to simulate a slow response that will trigger timeout
        // throttleBody(bytesPerPeriod, period, timeUnit) - very slow throughput triggers read timeout
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("x".repeat(1024))  // 1KB body
            .throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS))  // 1 byte per second

        try {
            shortTimeoutClient.postJson(
                url = server.url("/events").toString(),
                body = "{}".toByteArray(),
                timeoutMs = 200  // Ignored, uses construction-time timeout
            )
            fail("Expected timeout exception")
        } catch (e: IOException) {
            // Expected - either SocketTimeoutException or InterruptedIOException
            assertTrue(
                "Expected timeout-related exception, got: ${e.javaClass.simpleName}: ${e.message}",
                e is SocketTimeoutException ||
                e.message?.contains("timeout", ignoreCase = true) == true ||
                e.javaClass.simpleName.contains("Timeout", ignoreCase = true)
            )
        }
    }
}
