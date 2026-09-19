package com.waha.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WahaLinkProtocolTest {
    @Test
    fun roundTripSingleFrame() {
        val msg = LinkMessages.paymentRequest("ref-1", "34.20", "EGP")
        val frames = FrameDecoder().feed(FrameCodec.encode(msg))
        assertEquals(1, frames.size)
        val parsed = LinkMessages.parse(frames[0])
        assertEquals(MsgType.PAYMENT_REQUEST, parsed.type)
        assertEquals("ref-1", parsed.reference)
        assertEquals("34.20", parsed.amount)
        assertEquals("EGP", parsed.currency)
    }

    @Test
    fun partialReadsByteByByte() {
        val bytes = FrameCodec.encode(LinkMessages.hello(WahaLink.APP_KIOSK))
        val decoder = FrameDecoder()
        var got = 0
        for (b in bytes) got += decoder.feed(byteArrayOf(b)).size
        assertEquals(1, got)
    }

    @Test
    fun twoFramesInOneChunkPlusTrailingPartial() {
        val a = FrameCodec.encode(LinkMessages.ping("p1"))
        val b = FrameCodec.encode(LinkMessages.pong("p1"))
        val c = FrameCodec.encode(LinkMessages.cancel("ref-9"))
        val chunk = a + b + c.copyOfRange(0, 3)
        val decoder = FrameDecoder()
        val first = decoder.feed(chunk)
        assertEquals(2, first.size)
        val rest = decoder.feed(c.copyOfRange(3, c.size))
        assertEquals(1, rest.size)
        assertEquals(MsgType.CANCEL, LinkMessages.parse(rest[0]).type)
    }

    @Test
    fun badLengthIsFatalAndPoisonsDecoder() {
        val decoder = FrameDecoder()
        val huge = byteArrayOf(0x7F, 0x00, 0x00, 0x00)
        try { decoder.feed(huge); fail("expected exception") } catch (e: LinkProtocolException) {}
        try { decoder.feed(byteArrayOf(0)); fail("expected poisoned") } catch (e: LinkProtocolException) {}
    }

    @Test
    fun zeroLengthIsFatal() {
        try { FrameDecoder().feed(byteArrayOf(0, 0, 0, 0)); fail() } catch (e: LinkProtocolException) {}
    }

    @Test
    fun invalidJsonIsFatal() {
        val body = "not json".toByteArray()
        val frame = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        try { FrameDecoder().feed(frame); fail() } catch (e: LinkProtocolException) {}
    }

    @Test
    fun parseRejectsMalformedMessages() {
        fun bad(o: JSONObject) {
            try { LinkMessages.parse(o); fail("expected rejection: $o") } catch (e: LinkProtocolException) {}
        }
        bad(JSONObject().put("type", "nope").put("id", "1"))
        bad(JSONObject().put("type", MsgType.PING))
        bad(JSONObject().put("type", MsgType.HELLO).put("id", "1").put("app", "kiosk"))
        bad(JSONObject().put("type", MsgType.HELLO).put("id", "1").put("protocol", 1).put("app", "x"))
        bad(LinkMessages.paymentRequest("r", "12.3456", "EGP"))
        bad(LinkMessages.paymentRequest("r", "-5", "EGP"))
        bad(LinkMessages.paymentResponse("r", "weird"))
        bad(JSONObject().put("type", MsgType.CANCEL).put("id", "1"))
    }

    @Test
    fun paymentResponseFieldsSurviveRoundTrip() {
        val resp = LinkMessages.paymentResponse(
            "ref-2", Status.APPROVED, approvalCode = "A1B2", details = JSONObject().put("brand", "VISA"),
        )
        val parsed = LinkMessages.parse(FrameDecoder().feed(FrameCodec.encode(resp))[0])
        assertEquals(Status.APPROVED, parsed.status)
        assertEquals("A1B2", parsed.approvalCode)
        assertNull(parsed.errorCode)
        assertEquals("VISA", parsed.details?.getString("brand"))
    }

    @Test
    fun oversizedFrameRejectedOnEncode() {
        val big = JSONObject().put("type", MsgType.PING).put("id", "x".repeat(70_000))
        try { FrameCodec.encode(big); fail() } catch (e: LinkProtocolException) {}
    }

    @Test
    fun amountFormatting() {
        assertEquals("34.20", WahaLink.formatAmount(34.2))
        assertEquals("0.10", WahaLink.formatAmount(0.1))
        assertEquals("17.10", WahaLink.formatAmount(17.1))
        assertEquals("5.006", WahaLink.formatAmount(5.0055, 3))
        assertTrue(WahaLink.formatAmount(1e6).matches(Regex("\\d+\\.\\d\\d")))
    }
}
