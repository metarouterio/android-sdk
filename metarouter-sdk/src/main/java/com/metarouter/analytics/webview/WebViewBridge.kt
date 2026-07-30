package com.metarouter.analytics.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.metarouter.analytics.utils.Logger
import java.util.Collections
import java.util.WeakHashMap

/**
 * Registers the bridge on a host-owned WebView: the native message channel (receive
 * side) and the document-start wrapper script (producer side).
 *
 * `WebMessageListener` over `addJavascriptInterface`, deliberately: it scopes the
 * channel to allowlisted origins (an injectable JS object would let any script in any
 * page feed the native queue) and provides the reply channel the ack/error contract
 * needs. Both registrations only affect page loads that start after attach, which is
 * why hosts must attach before loadUrl.
 */
internal object WebViewBridge {

    // Origin rules must be scheme://host[:port] — anything else (paths, trailing
    // slashes, wildcards) either throws IllegalArgumentException inside the platform
    // API or silently never matches in the wrapper's exact-origin check.
    private val ORIGIN_RULE = Regex("^https?://[A-Za-z0-9.-]+(:\\d+)?$")

    // The platform has no API to remove a WebMessageListener, so a second attach on
    // the same WebView would throw on the duplicate JS object name. Weak keys: a
    // destroyed WebView must not be pinned by this bookkeeping.
    private val attached = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    )

    /**
     * Validates synchronously; registers on the main thread (WebView methods are
     * main-thread-only, and callers like proxy replay may arrive from IO).
     *
     * @return true if the attach was accepted — registration itself may still be
     *   pending a main-thread hop.
     */
    fun attach(
        webView: WebView,
        allowedOrigins: List<String>,
        processor: BridgeMessageProcessor
    ): Boolean {
        if (allowedOrigins.isEmpty()) {
            Logger.warn("attachWebView called with no allowed origins — ignoring.")
            return false
        }
        val invalid = allowedOrigins.filterNot { ORIGIN_RULE.matches(it) }
        if (invalid.isNotEmpty()) {
            // Origin scoping is the bridge's security boundary; a wildcard would let
            // any page loaded in this WebView (ads, redirects, hijacked navigation)
            // inject events into the native queue.
            Logger.warn(
                "attachWebView rejects invalid origin rules $invalid — " +
                    "use explicit scheme://host[:port] origins."
            )
            return false
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            Logger.warn(
                "WebView bridge unavailable: this device's WebView provider lacks " +
                    "WEB_MESSAGE_LISTENER/DOCUMENT_START_SCRIPT. Webview events will not be captured."
            )
            return false
        }
        if (!attached.add(webView)) {
            Logger.warn("attachWebView ignored: this WebView is already attached.")
            return false
        }

        runOnMainThread {
            try {
                register(webView, allowedOrigins, processor)
                Logger.log("WebView bridge attached (origins=$allowedOrigins)")
            } catch (e: Exception) {
                // A platform rejection must not crash the host app (or abort a proxy
                // bind replay) — the failure mode is no webview capture, logged.
                attached.remove(webView)
                Logger.error("WebView bridge attach failed: ${e.message}")
            }
        }
        return true
    }

    private fun register(
        webView: WebView,
        allowedOrigins: List<String>,
        processor: BridgeMessageProcessor
    ) {
        val originSet = allowedOrigins.toSet()

        WebViewCompat.addWebMessageListener(
            webView,
            BridgeWrapperScript.NATIVE_CHANNEL_NAME,
            originSet
        ) { _, message, _, _, replyProxy ->
            // Runs on the UI thread. The pipeline is cheap by design — bounded parse,
            // map lookup, non-blocking channel send — so no dispatch hop is needed
            // before acking.
            val raw = message.data
            if (raw != null) {
                val reply = processor.process(raw)
                replyProxy.postMessage(reply.toJson())
            }
        }

        WebViewCompat.addDocumentStartJavaScript(
            webView,
            BridgeWrapperScript.build(allowedOrigins),
            originSet
        )
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
}
