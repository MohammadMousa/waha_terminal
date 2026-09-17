package com.waha.waha_terminal

import android.hardware.usb.UsbManager
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

private const val TAG = "WahaTerminal"

class MainActivity : FlutterActivity() {
    private val methodChannelName = "com.waha.waha_terminal/usb_terminal"
    private val eventChannelName = "com.waha.waha_terminal/usb_terminal/events"

    private var usbTerminal: UsbTerminalManager? = null
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

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        usbTerminal?.stop()
        usbTerminal = null
        eventSink = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
