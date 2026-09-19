package com.waha.waha_terminal

import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
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

        // Device-side (AOA) link. The framed codec plugs in at
        // AccessoryStreamListener once the shared protocol file lands; until
        // then the streams are only logged so lifecycle can be tested.
        val acc = UsbAccessoryManager(
            context = this,
            usbManager = getSystemService(USB_SERVICE) as UsbManager,
            onEvent = { state, detail ->
                runOnUiThread { eventSink?.success(mapOf("state" to state, "detail" to detail)) }
            },
            listener = object : AccessoryStreamListener {
                override fun onStreamsOpened(input: InputStream, output: OutputStream) {
                    Log.i(TAG, "Accessory streams opened")
                }
                override fun onStreamsClosed(reason: String) {
                    Log.i(TAG, "Accessory streams closed: $reason")
                }
            },
        )
        acc.start()
        accessory = acc
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
        usbTerminal?.stop()
        usbTerminal = null
        eventSink = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
