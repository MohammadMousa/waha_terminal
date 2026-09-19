package com.waha.waha_terminal

import com.waha.link.ErrorCode
import com.waha.link.FrameCodec
import com.waha.link.FrameDecoder
import com.waha.link.LinkMessage
import com.waha.link.LinkMessages
import com.waha.link.LinkProtocolException
import com.waha.link.MsgType
import com.waha.link.Status
import com.waha.link.WahaLink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Everything the link reports upward. May be called from the reader thread or a caller's thread. */
interface LinkEvents {
    fun onLinkState(state: String, detail: String?)
    fun onPaymentRequest(reference: String, amount: String, currency: String)
    fun onCancel(reference: String)
    fun onLinkClosed(reason: String)
}

/**
 * Terminal side of the Waha kiosk<->terminal link (protocol in
 * com.waha.link). The kiosk drives every step; this class only answers:
 * hello both ways, pong for every ping (busy or idle), one payment at a time
 * with BUSY for a second request, cancel answered immediately.
 *
 * No Android framework types on purpose so it is unit-testable on the JVM.
 * Nothing here logs card data, amounts, references or payloads.
 */
class AccessoryLink(
    private val events: LinkEvents,
    private val requestTeardown: (reason: String) -> Unit,
    private val log: (String) -> Unit,
) : AccessoryStreamListener {

    private val stateLock = Any()
    private val writeLock = Any()

    @Volatile private var out: OutputStream? = null
    private var closed = true
    private var peerHello = false
    private var inFlightReference: String? = null

    override fun onStreamsOpened(input: InputStream, output: OutputStream) {
        synchronized(stateLock) {
            out = output
            closed = false
            peerHello = false
            inFlightReference = null
        }
        Thread({ readLoop(input) }, "waha-link-reader").apply { isDaemon = true }.start()
    }

    override fun onStreamsClosed(reason: String) {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            out = null
            peerHello = false
            inFlightReference = null
        }
        log("Link closed")
        events.onLinkClosed(reason)
    }

    private fun readLoop(input: InputStream) {
        if (!send(LinkMessages.hello(WahaLink.APP_TERMINAL))) return
        val decoder = FrameDecoder()
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    fail("end of stream", null)
                    return
                }
                for (frame in decoder.feed(buffer, n)) handle(LinkMessages.parse(frame))
            }
        } catch (e: LinkProtocolException) {
            fail("protocol error: ${e.message}", ErrorCode.INVALID_RESPONSE)
        } catch (e: IOException) {
            fail("read failed", null)
        }
    }

    private fun handle(msg: LinkMessage) {
        when (msg.type) {
            MsgType.PING -> send(LinkMessages.pong(msg.id))
            MsgType.PONG -> Unit
            MsgType.HELLO -> handleHello(msg)
            MsgType.PAYMENT_REQUEST -> {
                if (!requireHello(msg.type)) return
                handlePaymentRequest(msg)
            }
            MsgType.CANCEL -> {
                if (!requireHello(msg.type)) return
                handleCancel(msg)
            }
            // payment_response only ever flows terminal -> kiosk.
            else -> log("Ignoring unexpected ${msg.type}")
        }
    }

    private fun handleHello(msg: LinkMessage) {
        if (msg.protocol != WahaLink.PROTOCOL_VERSION) {
            fail("unsupported protocol version", ErrorCode.UNSUPPORTED_VERSION)
            return
        }
        if (msg.app != WahaLink.APP_KIOSK) {
            fail("hello from unexpected peer", ErrorCode.INVALID_RESPONSE)
            return
        }
        synchronized(stateLock) { peerHello = true }
        log("Link ready")
        events.onLinkState("ready", null)
    }

    // Neither side sends payment_* before receiving the peer's hello.
    private fun requireHello(type: String): Boolean {
        val ok = synchronized(stateLock) { peerHello }
        if (!ok) fail("$type before hello", ErrorCode.INVALID_RESPONSE)
        return ok
    }

    private fun handlePaymentRequest(msg: LinkMessage) {
        val reference = msg.reference!!
        val accepted = synchronized(stateLock) {
            if (inFlightReference != null) false else { inFlightReference = reference; true }
        }
        if (!accepted) {
            // Must not disturb the payment already in flight.
            log("Payment request rejected: busy")
            send(LinkMessages.paymentResponse(reference, Status.ERROR, errorCode = ErrorCode.BUSY))
            return
        }
        log("Payment request received")
        events.onPaymentRequest(reference, msg.amount!!, msg.currency!!)
    }

    private fun handleCancel(msg: LinkMessage) {
        val reference = msg.reference!!
        val matches = synchronized(stateLock) {
            if (inFlightReference == reference) { inFlightReference = null; true } else false
        }
        if (!matches) {
            log("Ignoring cancel for a payment that is not in flight")
            return
        }
        // Answer at once so the kiosk never waits on the NFC layer to notice.
        send(LinkMessages.paymentResponse(reference, Status.CANCELLED, errorCode = ErrorCode.CANCELLED))
        events.onCancel(reference)
    }

    /**
     * The one outbound payment answer. Returns false (and sends nothing) if
     * [reference] is not the payment currently in flight, e.g. it was already
     * cancelled — that is how a late NFC result is dropped.
     */
    fun sendPaymentResponse(
        reference: String,
        status: String,
        approvalCode: String? = null,
        errorCode: String? = null,
        message: String? = null,
        details: JSONObject? = null,
    ): Boolean {
        val current = synchronized(stateLock) {
            if (inFlightReference != reference) false else { inFlightReference = null; true }
        }
        if (!current) return false
        log("Payment response sent")
        return send(LinkMessages.paymentResponse(reference, status, approvalCode, errorCode, message, details))
    }

    private fun send(json: JSONObject): Boolean {
        var failed = false
        synchronized(writeLock) {
            val stream = out ?: return false
            try {
                stream.write(FrameCodec.encode(json))
                stream.flush()
            } catch (e: IOException) {
                failed = true
            }
        }
        if (failed) {
            fail("write failed", ErrorCode.WRITE_FAILED)
            return false
        }
        return true
    }

    // Any stream failure tears down at once; teardown closes the streams and
    // calls onStreamsClosed. Never called while holding a lock.
    private fun fail(reason: String, errorCode: String?) {
        val alreadyClosed = synchronized(stateLock) { closed }
        if (alreadyClosed) return
        log("Link failed: $reason")
        if (errorCode != null) events.onLinkState("error", errorCode)
        requestTeardown(reason)
    }
}
