package com.waha.waha_terminal

import com.waha.link.ErrorCode
import com.waha.link.FrameCodec
import com.waha.link.FrameDecoder
import com.waha.link.LinkMessages
import com.waha.link.MsgType
import com.waha.link.Status
import com.waha.link.WahaLink
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives AccessoryLink against a fake kiosk over in-memory pipes — no USB involved. */
class AccessoryLinkTest {

    private class Recorder : LinkEvents {
        val states = LinkedBlockingQueue<Pair<String, String?>>()
        val requests = LinkedBlockingQueue<Triple<String, String, String>>()
        val cancels = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<String>()
        override fun onLinkState(state: String, detail: String?) { states.add(state to detail) }
        override fun onPaymentRequest(reference: String, amount: String, currency: String) {
            requests.add(Triple(reference, amount, currency))
        }
        override fun onCancel(reference: String) { cancels.add(reference) }
        override fun onLinkClosed(reason: String) { closed.add(reason) }
    }

    private lateinit var events: Recorder
    private lateinit var link: AccessoryLink
    private lateinit var kioskOut: PipedOutputStream   // kiosk -> terminal
    private lateinit var terminalIn: PipedInputStream
    private lateinit var terminalOut: PipedOutputStream // terminal -> kiosk
    private lateinit var kioskIn: PipedInputStream
    private val fromTerminal = LinkedBlockingQueue<JSONObject>()

    @Before
    fun setUp() {
        events = Recorder()
        terminalIn = PipedInputStream(128 * 1024)
        kioskOut = PipedOutputStream(terminalIn)
        kioskIn = PipedInputStream(128 * 1024)
        terminalOut = PipedOutputStream(kioskIn)

        link = AccessoryLink(
            events = events,
            requestTeardown = { reason ->
                try { terminalIn.close() } catch (_: IOException) {}
                try { terminalOut.close() } catch (_: IOException) {}
                link.onStreamsClosed(reason)
            },
            log = {},
        )
        Thread {
            val decoder = FrameDecoder()
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = kioskIn.read(buf)
                    if (n < 0) break
                    decoder.feed(buf, n).forEach { fromTerminal.add(it) }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()

        link.onStreamsOpened(terminalIn, terminalOut)
    }

    @After
    fun tearDown() {
        try { kioskOut.close() } catch (_: IOException) {}
        try { kioskIn.close() } catch (_: IOException) {}
    }

    private fun kioskSend(json: JSONObject) {
        kioskOut.write(FrameCodec.encode(json))
        kioskOut.flush()
    }

    private fun nextFrame(): JSONObject =
        fromTerminal.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("no frame from terminal")

    private fun completeHandshake() {
        assertEquals(MsgType.HELLO, nextFrame().getString("type")) // unsolicited, on open
        kioskSend(LinkMessages.hello(WahaLink.APP_KIOSK))
        assertEquals("ready", events.states.poll(3, TimeUnit.SECONDS)?.first)
        assertEquals(MsgType.HELLO, nextFrame().getString("type")) // reply to the kiosk hello
    }

    @Test fun terminalSendsHelloFirstAndBecomesReadyOnKioskHello() {
        val hello = nextFrame()
        assertEquals(MsgType.HELLO, hello.getString("type"))
        assertEquals(WahaLink.APP_TERMINAL, hello.getString("app"))
        assertEquals(WahaLink.PROTOCOL_VERSION, hello.getInt("protocol"))
        kioskSend(LinkMessages.hello(WahaLink.APP_KIOSK))
        assertEquals("ready", events.states.poll(3, TimeUnit.SECONDS)?.first)
        val reply = nextFrame()
        assertEquals(MsgType.HELLO, reply.getString("type"))
        assertEquals(WahaLink.APP_TERMINAL, reply.getString("app"))
    }

    @Test fun everyKioskHelloIsAnsweredAndPaymentsStillWorkAfterward() {
        completeHandshake()
        // The kiosk reconnects before each payment and re-sends hello; the
        // stream stays open, so the terminal must answer again each time.
        for (i in 1..3) {
            kioskSend(LinkMessages.hello(WahaLink.APP_KIOSK))
            val reply = nextFrame()
            assertEquals(MsgType.HELLO, reply.getString("type"))
            assertEquals(WahaLink.APP_TERMINAL, reply.getString("app"))
        }
        // "ready" is reported once, not per hello.
        assertNull(events.states.poll(200, TimeUnit.MILLISECONDS))

        kioskSend(LinkMessages.paymentRequest("ord-1", "34.20", "EGP"))
        assertEquals("ord-1", events.requests.poll(3, TimeUnit.SECONDS)?.first)
        assertTrue(link.sendPaymentResponse("ord-1", Status.APPROVED, approvalCode = "123456"))
        assertEquals(Status.APPROVED, nextFrame().getString("status"))
    }

    @Test fun wrongProtocolVersionIsUnsupportedAndTearsDown() {
        nextFrame()
        kioskSend(LinkMessages.hello(WahaLink.APP_KIOSK).put("protocol", 99))
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, events.states.poll(3, TimeUnit.SECONDS)?.second)
        assertNotNull(events.closed.poll(3, TimeUnit.SECONDS))
    }

    @Test fun pingIsAnsweredWithMatchingIdBeforeAndAfterHello() {
        nextFrame()
        val early = LinkMessages.ping()
        kioskSend(early)
        val pong = nextFrame()
        assertEquals(MsgType.PONG, pong.getString("type"))
        assertEquals(early.getString("id"), pong.getString("id"))

        kioskSend(LinkMessages.hello(WahaLink.APP_KIOSK))
        events.states.poll(3, TimeUnit.SECONDS)
        assertEquals(MsgType.HELLO, nextFrame().getString("type")) // hello reply
        val late = LinkMessages.ping()
        kioskSend(late)
        assertEquals(late.getString("id"), nextFrame().getString("id"))
    }

    @Test fun paymentRequestBeforeHelloIsAProtocolViolation() {
        nextFrame()
        kioskSend(LinkMessages.paymentRequest("ord-1", "34.20", "EGP"))
        assertEquals(ErrorCode.INVALID_RESPONSE, events.states.poll(3, TimeUnit.SECONDS)?.second)
        assertNotNull(events.closed.poll(3, TimeUnit.SECONDS))
        assertNull(events.requests.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun paymentRequestReachesUpperLayerAndApprovedResponseIsSentOnce() {
        completeHandshake()
        kioskSend(LinkMessages.paymentRequest("ord-1", "34.20", "EGP"))
        assertEquals(Triple("ord-1", "34.20", "EGP"), events.requests.poll(3, TimeUnit.SECONDS))

        assertTrue(link.sendPaymentResponse("ord-1", Status.APPROVED, approvalCode = "123456"))
        val resp = nextFrame()
        assertEquals(MsgType.PAYMENT_RESPONSE, resp.getString("type"))
        assertEquals("ord-1", resp.getString("reference"))
        assertEquals(Status.APPROVED, resp.getString("status"))
        assertEquals("123456", resp.getString("approvalCode"))

        assertFalse(link.sendPaymentResponse("ord-1", Status.APPROVED, approvalCode = "123456"))
    }

    @Test fun secondRequestWhileInFlightGetsBusyAndDoesNotDisturbTheFirst() {
        completeHandshake()
        kioskSend(LinkMessages.paymentRequest("ord-1", "10.00", "EGP"))
        events.requests.poll(3, TimeUnit.SECONDS)

        kioskSend(LinkMessages.paymentRequest("ord-2", "20.00", "EGP"))
        val busy = nextFrame()
        assertEquals("ord-2", busy.getString("reference"))
        assertEquals(Status.ERROR, busy.getString("status"))
        assertEquals(ErrorCode.BUSY, busy.getString("errorCode"))
        assertNull(events.requests.poll(200, TimeUnit.MILLISECONDS))

        // ping still answered mid-payment
        val p = LinkMessages.ping()
        kioskSend(p)
        assertEquals(p.getString("id"), nextFrame().getString("id"))

        assertTrue(link.sendPaymentResponse("ord-1", Status.APPROVED, approvalCode = "000111"))
        assertEquals("ord-1", nextFrame().getString("reference"))
    }

    @Test fun cancelIsAnsweredImmediatelyAndLateResultIsDropped() {
        completeHandshake()
        kioskSend(LinkMessages.paymentRequest("ord-1", "10.00", "EGP"))
        events.requests.poll(3, TimeUnit.SECONDS)

        kioskSend(LinkMessages.cancel("ord-1"))
        val resp = nextFrame()
        assertEquals("ord-1", resp.getString("reference"))
        assertEquals(Status.CANCELLED, resp.getString("status"))
        assertEquals("ord-1", events.cancels.poll(3, TimeUnit.SECONDS))

        assertFalse(link.sendPaymentResponse("ord-1", Status.APPROVED, approvalCode = "999999"))
    }

    @Test fun cancelForAnotherReferenceIsIgnored() {
        completeHandshake()
        kioskSend(LinkMessages.paymentRequest("ord-1", "10.00", "EGP"))
        events.requests.poll(3, TimeUnit.SECONDS)
        kioskSend(LinkMessages.cancel("ord-other"))
        assertNull(events.cancels.poll(300, TimeUnit.MILLISECONDS))
        assertTrue(link.sendPaymentResponse("ord-1", Status.DECLINED, errorCode = ErrorCode.DECLINED))
    }

    @Test fun consecutivePaymentsReuseTheSameLink() {
        completeHandshake()
        for (i in 1..3) {
            val ref = "ord-$i"
            kioskSend(LinkMessages.paymentRequest(ref, "5.00", "EGP"))
            assertEquals(ref, events.requests.poll(3, TimeUnit.SECONDS)?.first)
            assertTrue(link.sendPaymentResponse(ref, Status.APPROVED, approvalCode = "00000$i"))
            assertEquals(ref, nextFrame().getString("reference"))
        }
        assertNull(events.closed.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun badFrameLengthIsFatal() {
        nextFrame()
        kioskOut.write(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F))
        kioskOut.flush()
        assertEquals(ErrorCode.INVALID_RESPONSE, events.states.poll(3, TimeUnit.SECONDS)?.second)
        assertNotNull(events.closed.poll(3, TimeUnit.SECONDS))
    }

    @Test fun peerGoneDuringWriteTearsDownWithWriteFailed() {
        completeHandshake()
        kioskIn.close() // kiosk stops reading; next terminal write fails
        kioskSend(LinkMessages.ping())
        assertEquals(ErrorCode.WRITE_FAILED, events.states.poll(3, TimeUnit.SECONDS)?.second)
        assertNotNull(events.closed.poll(3, TimeUnit.SECONDS))
    }

    @Test fun teardownDropsInFlightPaymentAndIsIdempotent() {
        completeHandshake()
        kioskSend(LinkMessages.paymentRequest("ord-1", "10.00", "EGP"))
        events.requests.poll(3, TimeUnit.SECONDS)
        link.onStreamsClosed("detached")
        link.onStreamsClosed("detached again")
        assertEquals("detached", events.closed.poll(3, TimeUnit.SECONDS))
        assertNull(events.closed.poll(200, TimeUnit.MILLISECONDS))
        assertFalse(link.sendPaymentResponse("ord-1", Status.APPROVED, approvalCode = "1"))
    }

    @Test fun oldReaderThreadFailureDoesNotKillAReopenedLink() {
        completeHandshake()

        // Reopen on a fresh pair of pipes, as a second open() of the accessory would.
        val newTerminalIn = PipedInputStream(128 * 1024)
        val newKioskOut = PipedOutputStream(newTerminalIn)
        val newKioskIn = PipedInputStream(128 * 1024)
        val newTerminalOut = PipedOutputStream(newKioskIn)
        val fromNew = LinkedBlockingQueue<JSONObject>()
        Thread {
            val d = FrameDecoder(); val b = ByteArray(4096)
            try { while (true) { val n = newKioskIn.read(b); if (n < 0) break; d.feed(b, n).forEach { fromNew.add(it) } } }
            catch (_: Exception) {}
        }.apply { isDaemon = true }.start()

        link.onStreamsClosed("reopen")
        link.onStreamsOpened(newTerminalIn, newTerminalOut)
        assertEquals(MsgType.HELLO, fromNew.poll(3, TimeUnit.SECONDS)?.getString("type"))

        // The old link's blocked read now fails; that must be ignored.
        // Closing the old WRITER end makes the old reader see end-of-stream
        // (a blocked PipedInputStream never notices its own side closing).
        kioskOut.close()
        Thread.sleep(1600)

        val ping = LinkMessages.ping()
        newKioskOut.write(FrameCodec.encode(ping)); newKioskOut.flush()
        assertEquals(ping.getString("id"), fromNew.poll(3, TimeUnit.SECONDS)?.getString("id"))
        assertNull(events.states.poll(200, TimeUnit.MILLISECONDS)?.takeIf { it.first == "error" })
    }
}
