package com.metarouter.analytics.webview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the JavaScript wrapper injected into attached webviews at document start.
 *
 * The wrapper defines `window.<bridgeObjectName>` with `track`/`page` methods, wraps
 * each call in an envelope — minting `messageId` and stamping the point-in-time page
 * facts at call time — and posts it as a JSON string through the native message channel
 * object that `addWebMessageListener` injects under [NATIVE_CHANNEL_NAME].
 * `messageId` must be minted here, on the producer side: an ID assigned at receipt
 * would give two deliveries of the same message two different IDs, defeating dedup.
 *
 * - The wrapper self-checks `location.origin` against the allowlist and defines nothing
 *   on non-allowlisted pages (defense in depth — the platform APIs are scoped too).
 * - `properties` may be an object or a JSON string (parsed by the wrapper). A string
 *   that fails to parse is forwarded as-is so the native validator rejects it with
 *   `malformed_payload` and the producer gets an error reply rather than silence.
 * - Calls made before the native channel exists (or with a blank name) are dropped.
 */
internal object BridgeWrapperScript {

    const val DEFAULT_BRIDGE_OBJECT_NAME = "metarouterBridge"

    /** JS object name `addWebMessageListener` injects; the wrapper posts through it. */
    const val NATIVE_CHANNEL_NAME = "__metaRouterNativeChannel"

    const val WRAPPER_VERSION = "1.0.0"

    fun build(
        allowedOrigins: List<String>,
        bridgeObjectName: String = DEFAULT_BRIDGE_OBJECT_NAME
    ): String {
        require(allowedOrigins.isNotEmpty()) { "allowedOrigins must not be empty" }
        require(bridgeObjectName.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]*$"))) {
            "bridgeObjectName must be a valid JS identifier"
        }
        val originsJsArray = JsonArray(allowedOrigins.map { JsonPrimitive(it) }).toString()

        return """
            (function() {
              'use strict';
              var ALLOWED_ORIGINS = $originsJsArray;
              if (ALLOWED_ORIGINS.indexOf(location.origin) === -1) { return; }
              if (window.$bridgeObjectName) { return; }

              function uuidv4() {
                if (window.crypto && window.crypto.getRandomValues) {
                  var b = new Uint8Array(16);
                  window.crypto.getRandomValues(b);
                  b[6] = (b[6] & 0x0f) | 0x40;
                  b[8] = (b[8] & 0x3f) | 0x80;
                  var h = [];
                  for (var i = 0; i < 16; i++) { h.push((b[i] + 0x100).toString(16).slice(1)); }
                  return h.slice(0, 4).join('') + '-' + h.slice(4, 6).join('') + '-' +
                         h.slice(6, 8).join('') + '-' + h.slice(8, 10).join('') + '-' +
                         h.slice(10, 16).join('');
                }
                return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
                  var r = Math.random() * 16 | 0;
                  return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
                });
              }

              function post(type, name, properties) {
                if (typeof name !== 'string' || name.length === 0) { return; }
                var props = properties;
                if (typeof props === 'string') {
                  try { props = JSON.parse(props); } catch (e) { /* forward as-is; native rejects */ }
                }
                if (props === undefined || props === null) { props = {}; }
                var envelope = {
                  version: 1,
                  messageId: uuidv4(),
                  type: type,
                  name: name,
                  properties: props,
                  sentAt: new Date().toISOString(),
                  page: {
                    url: location.href,
                    path: location.pathname,
                    search: location.search,
                    title: document.title,
                    referrer: document.referrer
                  },
                  source: { producer: 'wrapper', wrapperVersion: '$WRAPPER_VERSION' }
                };
                var channel = window.$NATIVE_CHANNEL_NAME;
                if (channel && typeof channel.postMessage === 'function') {
                  channel.postMessage(JSON.stringify(envelope));
                }
              }

              window.$bridgeObjectName = {
                track: function(name, properties) { post('track', name, properties); },
                page: function(name, properties) { post('page', name, properties); }
              };
            })();
        """.trimIndent()
    }
}
