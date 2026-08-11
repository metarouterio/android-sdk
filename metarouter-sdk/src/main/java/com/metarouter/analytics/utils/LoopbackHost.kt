package com.metarouter.analytics.utils

/**
 * Single definition of "local development host" for cleartext-http warnings —
 * the config validator and the webview bridge share the same policy, and a
 * second copy of this list is how the two would quietly diverge.
 */
internal object LoopbackHost {
    fun isLoopback(host: String): Boolean {
        // URL/URI report IPv6 literals with brackets; origin strings carry them too.
        val name = if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
        return name == "localhost" || name == "127.0.0.1" || name == "::1"
    }
}
