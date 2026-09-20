package com.waha.waha_terminal

import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.waha.link.ErrorCode
import com.waha.link.Status
import org.json.JSONObject
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

private const val TAG = "WahaTerminal"

class MainActivity : FlutterActivity() {
    private val methodChannelName = "com.waha.waha_terminal/usb_terminal"
    private val eventChannelName = "com.waha.waha_terminal/usb_terminal/events"

    private var usbTerminal: UsbTerminalManager? = null
    private var accessory: UsbAccessoryManager? = null
    private var accessoryLink: AccessoryLink? = null
    private var linkSink: EventChannel.EventSink? = null
    private val linkMethodChannelName = "com.waha.waha_terminal/usb_link"
    private val linkEventChannelName = "com.waha.waha_terminal/usb_link/events"

    private fun emitLink(payload: Map<String, Any?>) {
        runOnUiThread { linkSink?.success(payload) }
    }
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val manager = UsbTerminalManager(
            context = this,
            usbManager = getSystemService(USB_SERVICE) as UsbManager,
            onEvent = { state, detail ->
                runOnUiThread { eventSink?.success(mapOf("state" to state, "detail" to detail)) }
            },
        )
        manager.start()
        usbTerminal = manager

        // Device-side (AOA) link: the kiosk is the USB host and drives every
        // step; the framed protocol lives in AccessoryLink.
        var accRef: UsbAccessoryManager? = null
        val link = AccessoryLink(
            events = object : LinkEvents {
                override fun onLinkState(state: String, detail: String?) =
                    emitLink(mapOf("type" to "state", "state" to state, "detail" to detail))
                override fun onPaymentRequest(reference: String, amount: String, currency: String) {
                    runOnUiThread {
                        val sink = linkSink
                        if (sink == null) {
                            // The Dart side is not listening (not signed in, or not in USB
                            // mode yet): answer now rather than let the kiosk wait to time out.
                            Log.i(TAG, "Payment request refused: terminal app not ready")
                            accessoryLink?.sendPaymentResponse(
                                reference = reference,
                                status = Status.ERROR,
                                errorCode = ErrorCode.NOT_CONNECTED,
                                message = "Terminal app is not ready (sign in and set Connection Type to USB)",
                            )
                        } else {
                            sink.success(mapOf("type" to "paymentRequest", "reference" to reference,
                                "amount" to amount, "currency" to currency))
                        }
                    }
                }
                override fun onCancel(reference: String) =
                    emitLink(mapOf("type" to "cancel", "reference" to reference))
                override fun onLinkClosed(reason: String) =
                    emitLink(mapOf("type" to "linkClosed", "reason" to reason))
            },
            requestTeardown = { reason -> accRef?.teardown(reason) },
            log = { Log.i(TAG, it) },
        )
        val acc = UsbAccessoryManager(
            context = this,
            usbManager = getSystemService(USB_SERVICE) as UsbManager,
            onEvent = { state, detail ->
                emitLink(mapOf("type" to "state", "state" to state, "detail" to detail))
            },
            listener = link,
        )
        accRef = acc
        acc.start()
        accessory = acc
        accessoryLink = link

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, linkEventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                    linkSink = sink
                }
                override fun onCancel(arguments: Any?) {
                    linkSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, linkMethodChannelName)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "connect" -> { acc.connect(); result.success(null) }
                        "isLinkOpen" -> result.success(acc.isOpen())
                        "sendPaymentResponse" -> {
                            val details = call.argument<Map<String, Any?>>("details")
                            val sent = link.sendPaymentResponse(
                                reference = call.argument<String>("reference") ?: "",
                                status = call.argument<String>("status") ?: "",
                                approvalCode = call.argument<String>("approvalCode"),
                                errorCode = call.argument<String>("errorCode"),
                                message = call.argument<String>("message"),
                                details = details?.let { JSONObject(it) },
                            )
                            result.success(sent)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "usb_link call failed: ${e.javaClass.simpleName}")
                    result.error("LINK_ERROR", "USB link call failed", null)
                }
            }
        acc.handleAttachIntent(intent)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                    eventSink = sink
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                val mgr = usbTerminal
                if (mgr == null) {
                    result.error("NO_MANAGER", "USB manager not ready", null)
                    return@setMethodCallHandler
                }
                when (call.method) {
                    "connect" -> { mgr.connect(); result.success(null) }
                    "disconnect" -> { mgr.disconnect(); result.success(null) }
                    "isConnected" -> result.success(mgr.isConnected())
                    "isIdle" -> result.success(mgr.isIdle())
                    "requestPayment" -> {
                        val amount = call.argument<Double>("amount") ?: 0.0
                        val reference = call.argument<String>("reference") ?: ""
                        Log.i(TAG, "Payment request started")
                        mgr.requestPayment(amount, reference) { ok, error ->
                            runOnUiThread { result.success(mapOf("ok" to ok, "error" to error)) }
                        }
                    }
                    "receiveResponse" -> {
                        val timeoutMs = call.argument<Int>("timeoutMs") ?: 5000
                        mgr.receiveResponse(timeoutMs) { bytes, error ->
                            // Never log card numbers, PINs, secrets, or payment
                            // credentials — only the structural outcome.
                            Log.i(TAG, "Payment response received")
                            runOnUiThread {
                                result.success(mapOf(
                                    "data" to bytes?.let { String(it, Charsets.UTF_8) },
                                    "error" to error,
                                ))
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        accessory?.handleAttachIntent(intent)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        accessory?.stop()
        accessory = null
        accessoryLink = null
        linkSink = null
        usbTerminal?.stop()
        usbTerminal = null
        eventSink = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
