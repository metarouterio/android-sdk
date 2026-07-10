package com.metarouter.analytics.webview

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.metarouter.analytics.utils.Logger

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

    /**
     * @return true if the bridge was registered; false when the WebView provider on
     *   this device is too old for the required features (the SDK no-ops rather than
     *   falling back to an unscoped mechanism).
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
        if (allowedOrigins.contains("*")) {
            // Origin scoping is the bridge's security boundary; a wildcard would let any
            // page loaded in this WebView (ads, redirects, hijacked navigation) inject
            // events into the native queue.
            Logger.warn("attachWebView rejects the \"*\" origin — list explicit origins.")
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

        Logger.log("WebView bridge attached (origins=$allowedOrigins)")
        return true
    }
}
