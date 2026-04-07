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

    /** Queue of responses to return in order. Takes priority over nextResponse when non-empty. */
    private val responseQueue = ArrayDeque<NetworkResponse>()

    /** Queue of exceptions to throw in order. Takes priority over nextException when non-empty. */
    private val exceptionQueue = ArrayDeque<Exception>()

    /** Records all requests made for verification (thread-safe). */
    val requests: MutableList<Request> = CopyOnWriteArrayList()

    data class Request(
        val url: String,
        val body: ByteArray,
        val timeoutMs: Int,
        val additionalHeaders: Map<String, String>?
    )

    /** Enqueue a sequence of responses to return in order. */
    fun enqueueResponses(vararg responses: NetworkResponse) {
        responseQueue.addAll(responses)
    }

    /** Enqueue a sequence of exceptions to throw in order. */
    fun enqueueExceptions(vararg exceptions: Exception) {
        exceptionQueue.addAll(exceptions)
    }

    override suspend fun postJson(
        url: String,
        body: ByteArray,
        timeoutMs: Int,
        additionalHeaders: Map<String, String>?
    ): NetworkResponse {
        requests.add(Request(url, body, timeoutMs, additionalHeaders))

        // Exception queue takes priority
        if (exceptionQueue.isNotEmpty()) {
            throw exceptionQueue.removeFirst()
        }

        nextException?.let { throw it }

        // Response queue takes priority over static nextResponse
        if (responseQueue.isNotEmpty()) {
            return responseQueue.removeFirst()
        }

        return nextResponse
            ?: error("FakeNetworkClient: No response configured. Set nextResponse before calling postJson.")
    }

    /** Clear recorded requests and reset response/exception. */
    fun reset() {
        requests.clear()
        nextResponse = null
        nextException = null
        responseQueue.clear()
        exceptionQueue.clear()
    }
}
