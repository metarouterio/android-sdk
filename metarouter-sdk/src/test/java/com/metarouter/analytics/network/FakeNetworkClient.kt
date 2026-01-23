package com.metarouter.analytics.network

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake NetworkClient for unit testing.
 *
 * Configure responses before each test:
 * ```
 * val fake = FakeNetworkClient()
 * fake.nextResponse = NetworkResponse(200, emptyMap(), null)
 * ```
 *
 * Or configure exceptions:
 * ```
 * fake.nextException = IOException("Connection refused")
 * ```
 */
class FakeNetworkClient : NetworkClient {

    /** The next response to return. Set this before calling postJson. */
    var nextResponse: NetworkResponse? = null

    /** If set, postJson will throw this exception instead of returning a response. */
    var nextException: IOException? = null

    /** Records all requests made for verification (thread-safe). */
    val requests: MutableList<Request> = CopyOnWriteArrayList()

    data class Request(
        val url: String,
        val body: ByteArray,
        val timeoutMs: Int,
        val additionalHeaders: Map<String, String>?
    )

    override suspend fun postJson(
        url: String,
        body: ByteArray,
        timeoutMs: Int,
        additionalHeaders: Map<String, String>?
    ): NetworkResponse {
        requests.add(Request(url, body, timeoutMs, additionalHeaders))

        nextException?.let { throw it }

        return nextResponse
            ?: error("FakeNetworkClient: No response configured. Set nextResponse before calling postJson.")
    }

    /** Clear recorded requests and reset response/exception. */
    fun reset() {
        requests.clear()
        nextResponse = null
        nextException = null
    }
}
