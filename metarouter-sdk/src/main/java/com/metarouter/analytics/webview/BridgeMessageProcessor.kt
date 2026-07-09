package com.metarouter.analytics.webview

import com.metarouter.analytics.utils.Logger

/**
 * Receives validated, deduplicated envelopes for merge + enqueue. Implemented by the
 * client wiring; kept as a single-method seam so the processing pipeline is testable
 * without a real SDK instance.
 */
internal fun interface BridgeEventSink {
    fun enqueue(envelope: BridgeEnvelope)
}

/**
 * The receive pipeline for one attached webview: raw message → parse/validate → dedup →
 * sink, producing the reply to send back to the wrapper.
 *
 * Rejections are logged natively as well as replied — page JS consoles are rarely
 * watched in production, so logcat is the primary debugging surface for a
 * misbehaving integration.
 */
internal class BridgeMessageProcessor(
    private val sink: BridgeEventSink,
    private val dedupStore: BridgeDedupStore = BridgeDedupStore()
) {

    fun process(raw: String): BridgeReply {
        return when (val result = BridgeEnvelopeParser.parse(raw)) {
            is BridgeParseResult.Invalid -> {
                Logger.warn(
                    "WebView bridge message rejected (${result.code.wire}): ${result.message}"
                )
                BridgeReply.error(result)
            }

            is BridgeParseResult.Valid -> {
                val envelope = result.envelope
                if (!dedupStore.markIfNew(envelope.messageId)) {
                    // Duplicates are acked ok, not errored: the producer's goal —
                    // exactly-once enqueue — was already met by the first delivery.
                    Logger.log(
                        "WebView bridge duplicate dropped (messageId=${envelope.messageId})"
                    )
                    BridgeReply.ok(envelope.messageId)
                } else {
                    sink.enqueue(envelope)
                    BridgeReply.ok(envelope.messageId)
                }
            }
        }
    }
}
