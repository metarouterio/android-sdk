package com.metarouter.analytics.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ack / error reply sent back over the bridge's reply channel.
 *
 * Serialized shape:
 * - ok:    `{"status":"ok","messageId":"…"}`
 * - error: `{"status":"error","messageId":"…","code":"…","message":"…","supportedVersion":1}`
 *
 * Null fields are omitted from the wire form (e.g. `messageId` is absent when the
 * incoming message was unparseable).
 */
@Serializable
internal data class BridgeReply(
    val status: String,
    val messageId: String? = null,
    val code: String? = null,
    val message: String? = null,
    val supportedVersion: Int? = null
) {

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        fun ok(messageId: String) = BridgeReply(status = "ok", messageId = messageId)

        fun error(invalid: BridgeParseResult.Invalid) = BridgeReply(
            status = "error",
            messageId = invalid.messageId,
            code = invalid.code.wire,
            message = invalid.message,
            supportedVersion = invalid.supportedVersion
        )

        fun error(code: BridgeErrorCode, message: String, messageId: String? = null) = BridgeReply(
            status = "error",
            messageId = messageId,
            code = code.wire,
            message = message
        )
    }
}
