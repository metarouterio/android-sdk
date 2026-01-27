package com.metarouter.analytics.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp implementation of [NetworkClient].
 *
 * Uses a single OkHttpClient instance with connection pooling and default timeouts.
 * Timeouts are configured at construction to maximize connection reuse.
 */
class OkHttpNetworkClient(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : NetworkClient {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)  // Circuit breaker handles retries
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun postJson(
        url: String,
        body: ByteArray,
        timeoutMs: Int,
        additionalHeaders: Map<String, String>?
    ): NetworkResponse {
        val requestBody = body.toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")

        if (additionalHeaders != null) {
            for ((key, value) in additionalHeaders) {
                requestBuilder.header(key, value)
            }
        }

        val request = requestBuilder.build()

        return client.newCall(request).await()
    }

    /**
     * Suspending extension to execute OkHttp Call.
     */
    private suspend fun Call.await(): NetworkResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }

        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val headers = mutableMapOf<String, String>()
                for (i in 0 until response.headers.size) {
                    headers[response.headers.name(i)] = response.headers.value(i)
                }

                val body = response.body?.bytes()

                continuation.resume(NetworkResponse(
                    statusCode = response.code,
                    headers = headers,
                    body = body
                ))
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }
}
