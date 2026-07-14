package com.metarouter.analytics.webview

import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.PageContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Error codes returned to the web producer. Closed set — the web side may branch on
 * these, so adding a code is a contract change. [wire] is the exact string that
 * appears in the reply payload.
 */
internal enum class BridgeErrorCode(val wire: String) {
    MALFORMED_JSON("malformed_json"),
    MALFORMED_PAYLOAD("malformed_payload"),
    MISSING_FIELD("missing_field"),
    UNKNOWN_TYPE("unknown_type"),
    UNSUPPORTED_VERSION("unsupported_version"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    NOT_READY("not_ready")
}

internal sealed class BridgeParseResult {
    data class Valid(val envelope: BridgeEnvelope) : BridgeParseResult()

    data class Invalid(
        val code: BridgeErrorCode,
        val message: String,
        /** Echoed when it could be extracted — absent for unparseable input. */
        val messageId: String? = null,
        /** Populated only for [BridgeErrorCode.UNSUPPORTED_VERSION]. */
        val supportedVersion: Int? = null
    ) : BridgeParseResult()
}

/**
 * Parses and validates a raw bridge message into a [BridgeEnvelope].
 *
 * Validation order: size limit (checked before parsing, so an oversized payload is
 * never deserialized) → JSON well-formedness → required fields → version rule → type
 * rule. Unknown fields are ignored so the web side can add optional fields without a
 * version bump; optional fields with an unexpected JSON type are treated as absent
 * rather than rejected — only `properties` (which downstream merge depends on) is
 * strict.
 */
internal object BridgeEnvelopeParser {

    const val SUPPORTED_VERSION = 1
    const val MAX_ENVELOPE_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): BridgeParseResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) {
            return BridgeParseResult.Invalid(
                BridgeErrorCode.PAYLOAD_TOO_LARGE,
                "envelope exceeds $MAX_ENVELOPE_BYTES bytes"
            )
        }

        val root: JsonObject = try {
            val element = json.parseToJsonElement(raw)
            element as? JsonObject
                ?: return BridgeParseResult.Invalid(
                    BridgeErrorCode.MALFORMED_PAYLOAD,
                    "envelope must be a JSON object"
                )
        } catch (e: Exception) {
            return BridgeParseResult.Invalid(
                BridgeErrorCode.MALFORMED_JSON,
                "envelope is not valid JSON"
            )
        }

        // Extract messageId first so later rejections can echo it in the error reply.
        val messageId = root.stringOrNull("messageId")
        if (messageId.isNullOrBlank()) {
            return BridgeParseResult.Invalid(
                BridgeErrorCode.MISSING_FIELD,
                "messageId is required"
            )
        }

        val versionElement = root["version"]
            ?: return missing("version", messageId)
        val version = (versionElement as? JsonPrimitive)?.intOrNull
            ?: return BridgeParseResult.Invalid(
                BridgeErrorCode.MALFORMED_PAYLOAD,
                "version must be an integer",
                messageId
            )
        if (version < 1) {
            return BridgeParseResult.Invalid(
                BridgeErrorCode.MALFORMED_PAYLOAD,
                "version must be >= 1",
                messageId
            )
        }
        if (version > SUPPORTED_VERSION) {
            return BridgeParseResult.Invalid(
                BridgeErrorCode.UNSUPPORTED_VERSION,
                "envelope version $version > supported $SUPPORTED_VERSION",
                messageId,
                supportedVersion = SUPPORTED_VERSION
            )
        }

        val typeString = root.stringOrNull("type")
            ?: return missing("type", messageId)
        val type = when (typeString) {
            "track" -> EventType.TRACK
            "page" -> EventType.PAGE
            else -> return BridgeParseResult.Invalid(
                BridgeErrorCode.UNKNOWN_TYPE,
                "type must be \"track\" or \"page\", got \"$typeString\"",
                messageId
            )
        }

        val name = root.stringOrNull("name")
        if (name.isNullOrBlank()) {
            return missing("name", messageId)
        }

        val propertiesElement = root["properties"]
        val properties: Map<String, JsonElement> =
            when (propertiesElement) {
                null -> emptyMap()
                is JsonObject -> propertiesElement
                else -> return BridgeParseResult.Invalid(
                    BridgeErrorCode.MALFORMED_PAYLOAD,
                    "properties must be a JSON object",
                    messageId
                )
            }

        val page = (root["page"] as? JsonObject)?.let {
            PageContext(
                url = it.stringOrNull("url"),
                title = it.stringOrNull("title"),
                referrer = it.stringOrNull("referrer")
            )
        }

        val source = (root["source"] as? JsonObject)?.let {
            BridgeSource(
                producer = it.stringOrNull("producer"),
                wrapperVersion = it.stringOrNull("wrapperVersion")
            )
        }

        return BridgeParseResult.Valid(
            BridgeEnvelope(
                version = version,
                messageId = messageId,
                type = type,
                name = name,
                properties = properties,
                sentAt = root.stringOrNull("sentAt"),
                page = page,
                source = source
            )
        )
    }

    private fun missing(field: String, messageId: String?) = BridgeParseResult.Invalid(
        BridgeErrorCode.MISSING_FIELD,
        "$field is required",
        messageId
    )

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
