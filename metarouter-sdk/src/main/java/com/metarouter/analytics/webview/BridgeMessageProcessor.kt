package com.metarouter.analytics.webview

import com.metarouter.analytics.utils.Logger

/**
 * Receives validated, deduplicated envelopes for merge + enqueue. Implemented by the
 * client wiring; kept as a single-method seam so the processing pipeline is testable
 * without a real SDK instance.
 *
 * Returns whether the event actually entered the delivery path — an `ok` ack for an
 * event that was silently dropped (SDK resetting, channel full) would lie to the
 * producer, so the reply must reflect this result.
 */
internal fun interface BridgeEventSink {
    fun enqueue(envelope: BridgeEnvelope): Boolean
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

    /**
     * Synchronous; the caller chooses the thread. NOT safe to call concurrently for the
     * same processor — `markIfNew → enqueue → forget` is a check-then-act across two lock
     * regions, so a concurrent duplicate could be acked `ok` and dropped while the
     * original's enqueue fails, losing the event. Invoke from a single confined thread.
     */
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
                } else if (sink.enqueue(envelope)) {
                    BridgeReply.ok(envelope.messageId)
                } else {
                    // The event never entered the delivery path — un-record the id so
                    // a producer retry is not misread as a duplicate of a message that
                    // was in fact lost.
                    dedupStore.forget(envelope.messageId)
                    Logger.warn(
                        "WebView bridge event not accepted (messageId=${envelope.messageId})"
                    )
                    BridgeReply.error(
                        BridgeErrorCode.NOT_READY,
                        "SDK not ready to accept events",
                        envelope.messageId
                    )
                }
            }
        }
    }
}
