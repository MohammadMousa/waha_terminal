package com.waha.link

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

// Shared wire protocol between the Waha kiosk (USB host) and Waha Terminal
// (USB accessory/device). Copied VERBATIM into both apps — do not edit one
// copy without the other. Pure Kotlin + org.json, no Android framework
// dependencies beyond that, so it can be reused as-is on either end.
//
// Frame = 4-byte big-endian length + UTF-8 JSON body. Max body 64 KB. A bad
// length or invalid JSON is fatal: the receiver tears the link down rather
// than trying to resync mid-stream (see FrameDecoder).
//
// JSON key "type" carries the message type (snake_case values); every other
// field is camelCase. Every message carries a sender-generated "id".

object WahaLink {
    const val PROTOCOL_VERSION = 1
    const val MAX_FRAME_BYTES = 64 * 1024

    // AOA handshake strings — kiosk sends them, terminal's accessory_filter
    // matches them.
    const val ACCESSORY_MANUFACTURER = "Waha"
    const val ACCESSORY_MODEL = "WahaTerminal"
    const val ACCESSORY_VERSION = "1"

    const val APP_KIOSK = "kiosk"
    const val APP_TERMINAL = "terminal"

    fun newId(): String = UUID.randomUUID().toString()

    // Amount travels as a decimal STRING in major units, never a JSON number,
    // so no double formatting drift between the two ends.
    fun formatAmount(amount: Double, decimals: Int = 2): String =
        BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
}

object MsgType {
    const val HELLO = "hello"
    const val PING = "ping"
    const val PONG = "pong"
    const val PAYMENT_REQUEST = "payment_request"
    const val PAYMENT_RESPONSE = "payment_response"
    const val CANCEL = "cancel"

    val all = setOf(HELLO, PING, PONG, PAYMENT_REQUEST, PAYMENT_RESPONSE, CANCEL)
}

object Status {
    const val APPROVED = "approved"
    const val DECLINED = "declined"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"

    val all = setOf(APPROVED, DECLINED, ERROR, CANCELLED)
}

// One code per failure, never reused for a different failure.
object ErrorCode {
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val INVALID_RESPONSE = "INVALID_RESPONSE"
    const val DECLINED = "DECLINED"
    const val CANCELLED = "CANCELLED"
    const val BUSY = "BUSY"
    const val NFC_UNAVAILABLE = "NFC_UNAVAILABLE"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
}

// Thrown for anything that means the byte stream can no longer be trusted
// (bad length, invalid JSON, malformed message). Receiver must tear down.
class LinkProtocolException(message: String) : Exception(message)

// A validated message. [json] is the full object for reading optional fields.
class LinkMessage(val type: String, val id: String, val json: JSONObject) {
    val reference: String? get() = json.optString("reference").ifEmpty { null }
    val status: String? get() = json.optString("status").ifEmpty { null }
    val errorCode: String? get() = json.optString("errorCode").ifEmpty { null }
    val message: String? get() = json.optString("message").ifEmpty { null }
    val approvalCode: String? get() = json.optString("approvalCode").ifEmpty { null }
    val amount: String? get() = json.optString("amount").ifEmpty { null }
    val currency: String? get() = json.optString("currency").ifEmpty { null }
    val app: String? get() = json.optString("app").ifEmpty { null }
    val protocol: Int get() = json.optInt("protocol", -1)
    val details: JSONObject? get() = json.optJSONObject("details")
}

object LinkMessages {
    private val amountPattern = Regex("^\\d{1,9}(\\.\\d{1,3})?$")

    fun hello(app: String, id: String = WahaLink.newId()): JSONObject =
        JSONObject().put("type", MsgType.HELLO).put("id", id)
            .put("protocol", WahaLink.PROTOCOL_VERSION).put("app", app)

    fun ping(id: String = WahaLink.newId()): JSONObject =
        JSONObject().put("type", MsgType.PING).put("id", id)

    // pong echoes the ping's id.
    fun pong(pingId: String): JSONObject =
        JSONObject().put("type", MsgType.PONG).put("id", pingId)

    fun paymentRequest(
        reference: String,
        amount: String,
        currency: String,
        id: String = WahaLink.newId(),
    ): JSONObject =
        JSONObject().put("type", MsgType.PAYMENT_REQUEST).put("id", id)
            .put("reference", reference).put("amount", amount).put("currency", currency)

    fun paymentResponse(
        reference: String,
        status: String,
        approvalCode: String? = null,
        errorCode: String? = null,
        message: String? = null,
        details: JSONObject? = null,
        id: String = WahaLink.newId(),
    ): JSONObject {
        val o = JSONObject().put("type", MsgType.PAYMENT_RESPONSE).put("id", id)
            .put("reference", reference).put("status", status)
        if (approvalCode != null) o.put("approvalCode", approvalCode)
        if (errorCode != null) o.put("errorCode", errorCode)
        if (message != null) o.put("message", message)
        if (details != null) o.put("details", details)
        return o
    }

    fun cancel(reference: String, id: String = WahaLink.newId()): JSONObject =
        JSONObject().put("type", MsgType.CANCEL).put("id", id).put("reference", reference)

    // Validates shape only; semantic checks (version match, reference match)
    // belong to the caller. Throws LinkProtocolException on a malformed message.
    fun parse(json: JSONObject): LinkMessage {
        val type = json.optString("type")
        if (type !in MsgType.all) throw LinkProtocolException("unknown type '$type'")
        val id = json.optString("id")
        if (id.isEmpty()) throw LinkProtocolException("missing id on $type")
        val msg = LinkMessage(type, id, json)
        when (type) {
            MsgType.HELLO -> {
                if (msg.protocol < 0) throw LinkProtocolException("hello without protocol")
                if (msg.app != WahaLink.APP_KIOSK && msg.app != WahaLink.APP_TERMINAL)
                    throw LinkProtocolException("hello with bad app '${msg.app}'")
            }
            MsgType.PAYMENT_REQUEST -> {
                if (msg.reference == null) throw LinkProtocolException("request without reference")
                val a = msg.amount
                if (a == null || !amountPattern.matches(a))
                    throw LinkProtocolException("request with bad amount '$a'")
                if (msg.currency == null) throw LinkProtocolException("request without currency")
            }
            MsgType.PAYMENT_RESPONSE -> {
                if (msg.reference == null) throw LinkProtocolException("response without reference")
                if (msg.status == null || msg.status !in Status.all)
                    throw LinkProtocolException("response with bad status '${msg.status}'")
            }
            MsgType.CANCEL -> {
                if (msg.reference == null) throw LinkProtocolException("cancel without reference")
            }
        }
        return msg
    }
}

object FrameCodec {
    // Encodes one JSON object as a length-prefixed frame.
    fun encode(json: JSONObject): ByteArray {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        if (body.size > WahaLink.MAX_FRAME_BYTES)
            throw LinkProtocolException("frame too large: ${body.size}")
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }
}

// Push-style decoder: feed it whatever chunk a read returned (USB bulk reads
// arrive in packet-sized pieces, stream reads in arbitrary pieces) and it
// returns every COMPLETE frame decoded so far, buffering the remainder.
// Any bad length or invalid JSON throws LinkProtocolException and leaves the
// decoder unusable — the caller tears the link down and never resyncs.
class FrameDecoder {
    private val buffer = ByteArrayOutputStream()
    private var poisoned = false

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<JSONObject> {
        if (poisoned) throw LinkProtocolException("decoder poisoned by earlier error")
        buffer.write(bytes, 0, length)
        val out = ArrayList<JSONObject>()
        var data = buffer.toByteArray()
        var offset = 0
        while (data.size - offset >= 4) {
            val len = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (len <= 0 || len > WahaLink.MAX_FRAME_BYTES) {
                poisoned = true
                throw LinkProtocolException("bad frame length $len")
            }
            if (data.size - offset - 4 < len) break // partial frame, wait for more
            val body = String(data, offset + 4, len, Charsets.UTF_8)
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                poisoned = true
                throw LinkProtocolException("invalid JSON in frame")
            }
            out.add(json)
            offset += 4 + len
        }
        buffer.reset()
        if (offset < data.size) buffer.write(data, offset, data.size - offset)
        return out
    }
}
