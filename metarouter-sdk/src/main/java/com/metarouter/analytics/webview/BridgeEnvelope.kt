package com.metarouter.analytics.webview

import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.PageContext
import kotlinx.serialization.json.JsonElement

/**
 * A validated web→native bridge envelope (contract v1).
 *
 * Instances are only produced by [BridgeEnvelopeParser.parse] — construction implies the
 * required-field, version, and type rules have already been enforced, so downstream code
 * (merge, dedup) never re-validates.
 *
 * `version`, `sentAt`, and `source` are contract fields carried but not yet consumed:
 * `version` feeds the parser's compatibility gate, `sentAt` is the producer's untrusted
 * clock (native sets the real timestamp at merge), and `source` distinguishes the
 * injected wrapper from the future web-SDK producer.
 */
internal data class BridgeEnvelope(
    val version: Int,
    val messageId: String,
    /** Restricted to [EventType.TRACK] and [EventType.PAGE] in contract v1. */
    val type: EventType,
    val name: String,
    val properties: Map<String, JsonElement>,
    val sentAt: String?,
    /** Point-in-time page facts stamped by the wrapper at call time. */
    val page: PageContext?,
    val source: BridgeSource?
)

/**
 * Producer identification — distinguishes the injected wrapper from the future
 * web-SDK proxy mode (F2).
 */
internal data class BridgeSource(
    val producer: String?,
    val wrapperVersion: String?
)
