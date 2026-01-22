package com.metarouter.analytics.network

import java.io.IOException

/**
 * HTTP response from the network layer.
 *
 * @property statusCode HTTP status code (e.g., 200, 404, 500)
 * @property headers Response headers (case-preserved keys)
 * @property body Response body bytes, or null if empty
 */
data class NetworkResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NetworkResponse) return false
        return statusCode == other.statusCode &&
                headers == other.headers &&
                body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Network client interface for HTTP operations.
 *
 * Implementations should:
 * - Return [NetworkResponse] for all HTTP responses (including 4xx, 5xx)
 * - Throw [IOException] only for transport-level failures (connection refused, DNS, timeout, SSL)
 */
interface NetworkClient {

    /**
     * POST JSON data to the specified URL.
     *
     * @param url Full URL to POST to
     * @param body JSON body as bytes
     * @param timeoutMs Request timeout in milliseconds
     * @param additionalHeaders Optional extra headers to include
     * @return Network response with status code, headers, and body
     * @throws IOException for transport failures (not HTTP errors)
     */
    @Throws(IOException::class)
    suspend fun postJson(
        url: String,
        body: ByteArray,
        timeoutMs: Int,
        additionalHeaders: Map<String, String>? = null
    ): NetworkResponse
}
