package com.metarouter.analytics.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeMessageProcessorTest {

    private class RecordingSink : BridgeEventSink {
        var accept = true
        val enqueued = mutableListOf<BridgeEnvelope>()
        override fun enqueue(envelope: BridgeEnvelope): Boolean {
            if (accept) enqueued.add(envelope)
            return accept
        }
    }

    private fun envelopeJson(messageId: String = "m-1", name: String = "product_viewed") =
        """{"version":1,"messageId":"$messageId","type":"track","name":"$name","properties":{"sku":"SKU-1"}}"""

    @Test
    fun `valid envelope is enqueued and acked ok`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        val reply = processor.process(envelopeJson())

        assertEquals(1, sink.enqueued.size)
        assertEquals("product_viewed", sink.enqueued[0].name)
        assertEquals("ok", reply.status)
        assertEquals("m-1", reply.messageId)
    }

    @Test
    fun `invalid envelope is rejected and never reaches the sink`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        val reply = processor.process("{not json")

        assertEquals(0, sink.enqueued.size)
        assertEquals("error", reply.status)
        assertEquals("malformed_json", reply.code)
    }

    @Test
    fun `validation failure carries the specific error code`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        val reply = processor.process(
            """{"version":1,"messageId":"m-1","type":"identify","name":"x"}"""
        )

        assertEquals(0, sink.enqueued.size)
        assertEquals("unknown_type", reply.code)
        assertEquals("m-1", reply.messageId)
    }

    @Test
    fun `duplicate messageId is dropped but still acked ok`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        val first = processor.process(envelopeJson(messageId = "m-dup"))
        val second = processor.process(envelopeJson(messageId = "m-dup"))

        assertEquals(1, sink.enqueued.size)
        assertEquals("ok", first.status)
        assertEquals("ok", second.status)
    }

    @Test
    fun `distinct messageIds are all enqueued`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        processor.process(envelopeJson(messageId = "m-1"))
        processor.process(envelopeJson(messageId = "m-2"))
        processor.process(envelopeJson(messageId = "m-3"))

        assertEquals(listOf("m-1", "m-2", "m-3"), sink.enqueued.map { it.messageId })
    }

    @Test
    fun `rejected message does not poison the dedup store`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        // Same messageId, first with an invalid type: the rejection must not record
        // the id, or the corrected retry would be dropped as a duplicate.
        processor.process("""{"version":1,"messageId":"m-1","type":"bogus","name":"x"}""")
        val retry = processor.process(envelopeJson(messageId = "m-1"))

        assertEquals(1, sink.enqueued.size)
        assertEquals("ok", retry.status)
    }

    @Test
    fun `rejected enqueue NAKs not_ready and allows a retry`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)
        sink.accept = false

        val first = processor.process(envelopeJson(messageId = "m-r"))

        assertEquals("error", first.status)
        assertEquals("not_ready", first.code)

        // The failed delivery must not poison dedup: the retry succeeds once the
        // sink accepts again.
        sink.accept = true
        val retry = processor.process(envelopeJson(messageId = "m-r"))

        assertEquals("ok", retry.status)
        assertEquals(1, sink.enqueued.size)
    }

    @Test
    fun `sink receives the parsed envelope not the raw string`() {
        val sink = RecordingSink()
        val processor = BridgeMessageProcessor(sink)

        processor.process(envelopeJson())

        val envelope = sink.enqueued[0]
        assertTrue(envelope.properties.containsKey("sku"))
        assertEquals(1, envelope.version)
    }
}
