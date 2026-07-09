package com.metarouter.analytics.webview

import com.metarouter.analytics.types.EventType
import kotlinx.serialization.json.JsonElement

/**
 * A validated web→native bridge envelope (contract v1).
 *
 * Instances are only produced by [BridgeEnvelopeParser.parse] — construction implies the
 * required-field, version, and type rules have already been enforced, so downstream code
 * (merge, dedup) never re-validates.
 */
internal data class BridgeEnvelope(
    val version: Int,
    val messageId: String,
    /** Restricted to [EventType.TRACK] and [EventType.PAGE] in contract v1. */
    val type: EventType,
    val name: String,
    val properties: Map<String, JsonElement>,
    /** Producer clock, informational only — native sets the event timestamp at merge. */
    val sentAt: String?,
    val page: BridgePageContext?,
    val source: BridgeSource?
)

/**
 * Point-in-time page facts stamped by the wrapper at call time.
 */
internal data class BridgePageContext(
    val url: String?,
    val title: String?,
    val referrer: String?
)

/**
 * Producer identification — distinguishes the injected wrapper from the future
 * web-SDK proxy mode (F2).
 */
internal data class BridgeSource(
    val producer: String?,
    val wrapperVersion: String?
)
