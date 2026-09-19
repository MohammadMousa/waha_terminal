package com.waha.waha_terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Generic USB host transport for waha_terminal ("waha-terminal" per the USB
 * Payment Transport Evaluation doc). This is NOT a Geidea/PAX implementation
 * and must never claim protocol compatibility with either — no vendor
 * command set is implemented here, only a generic bulk-transfer echo.
 *
 * Exists to exercise the same class of Android USB plumbing bugs the Geidea
 * integration hits (stale cached connection state, permission /
 * registerReceiver lifecycle, reconnect-after-unplug) against any attached
 * USB peripheral, without needing Geidea hardware.
 *
 * Deliberately never trusts a standalone "isConnected" boolean that could go
 * stale between a lifecycle callback and the next check — [isConnected]
 * always re-derives from the live UsbManager device list plus whether
 * [connection] is still open, and a detach broadcast immediately tears the
 * connection down rather than waiting for the next explicit check.
 */
class UsbTerminalManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onEvent: (String, String?) -> Unit,
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.waha.waha_terminal.USB_PERMISSION"
        private const val TAG = "WahaTerminal"
    }

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var registered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && dev != null) {
                Log.i(TAG, "USB permission granted")
                onEvent("permissionGranted", null)
                openDevice(dev)
            } else {
                Log.i(TAG, "USB permission denied")
                onEvent("permissionDenied", "USB permission denied for ${dev?.deviceName ?: "device"}")
            }
        }
    }

    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device detected")
                    onEvent("deviceAttached", dev?.deviceName)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (dev != null && dev.deviceName == device?.deviceName) {
                        teardownConnection()
                        Log.i(TAG, "USB disconnected")
                        onEvent("disconnected", "USB device detached")
                    }
            }
        }
    }

    /** Registers both receivers — call once, from configureFlutterEngine. */
    fun start() {
        if (registered) return
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(attachDetachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, permissionFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(attachDetachReceiver, attachFilter)
        }
        registered = true
    }

    /** Unregisters both receivers — call once, from cleanUpFlutterEngine. */
    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        try { context.unregisterReceiver(attachDetachReceiver) } catch (_: IllegalArgumentException) {}
        registered = false
        teardownConnection()
        ioExecutor.shutdown()
    }

    /** Picks the first attached USB device (generic — no vendor/product filter) and opens/requests it. */
    fun connect() {
        try {
            val candidate = usbManager.deviceList.values.firstOrNull()
            if (candidate == null) {
                onEvent("error", "No USB device attached")
                return
            }
            if (usbManager.hasPermission(candidate)) {
                openDevice(candidate)
                return
            }
            onEvent("permissionRequested", candidate.deviceName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags,
            )
            usbManager.requestPermission(candidate, permissionIntent)
        } catch (e: Exception) {
            Log.i(TAG, "USB connect failed: ${e.message}")
            onEvent("error", "USB connect failed")
        }
    }

    // Wrapped end to end: some devices/vendor builds throw from openDevice()
    // or claimInterface() instead of returning null/false on failure (seen
    // in the field as a bare IOException whose message is just the raw
    // /dev/bus/usb/... node path — unusable if it reaches the UI verbatim).
    // Every failure here must go through onEvent("error", <clear message>)
    // so nothing native ever surfaces to Dart unformatted.
    private fun openDevice(dev: UsbDevice) {
        try {
            val iface = (0 until dev.interfaceCount)
                .map { dev.getInterface(it) }
                .firstOrNull { it.endpointCount > 0 }
            val conn = usbManager.openDevice(dev)
            if (conn == null || iface == null) {
                onEvent("error", "Failed to open USB device or no usable interface")
                return
            }
            if (!conn.claimInterface(iface, true)) {
                onEvent("error", "Failed to claim USB interface")
                conn.close()
                return
            }
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
            }
            device = dev
            connection = conn
            usbInterface = iface
            endpointIn = inEp
            endpointOut = outEp
            Log.i(TAG, "USB connection opened")
            onEvent("connected", dev.deviceName)
        } catch (e: Exception) {
            Log.i(TAG, "USB connection open failed: ${e.message}")
            onEvent("error", "Could not open USB device — it may be held by another app or just unplugged")
        }
    }

    fun disconnect() {
        teardownConnection()
        Log.i(TAG, "USB connection closed")
        onEvent("disconnected", "Disconnected by app")
    }

    private fun teardownConnection() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}
        device = null
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    /** Always re-derived live — never trust a cached flag across a lifecycle gap. */
    fun isConnected(): Boolean {
        val dev = device ?: return false
        val stillAttached = usbManager.deviceList.values.any { it.deviceName == dev.deviceName }
        return stillAttached && connection != null
    }

    fun isIdle(): Boolean = isConnected()

    /** Writes a small generic JSON payload — not a Geidea/PAX command. Requires a bulk OUT endpoint. */
    fun requestPayment(amount: Double, reference: String, callback: (Boolean, String?) -> Unit) {
        val conn = connection
        val out = endpointOut
        if (conn == null || out == null) {
            callback(false, "No bulk OUT endpoint on this USB device — payment request cannot be sent")
            return
        }
        ioExecutor.execute {
            // Must always invoke callback exactly once — a thrown (not just
            // negative-return) bulkTransfer here would otherwise leave the
            // Dart-side invokeMethod() awaiting forever, with no error and
            // no timeout of its own.
            try {
                val payload = "{\"cmd\":\"payment\",\"amount\":$amount,\"reference\":\"$reference\"}"
                    .toByteArray(StandardCharsets.UTF_8)
                val sent = conn.bulkTransfer(out, payload, payload.size, 5000)
                callback(sent >= 0, if (sent < 0) "USB write failed" else null)
            } catch (e: Exception) {
                Log.i(TAG, "USB write threw: ${e.message}")
                callback(false, "USB write failed")
            }
        }
    }

    /** Reads a bounded response — requires a bulk IN endpoint. Blocks the IO executor thread, not the UI thread. */
    fun receiveResponse(timeoutMs: Int, callback: (ByteArray?, String?) -> Unit) {
        val conn = connection
        val inEp = endpointIn
        if (conn == null || inEp == null) {
            callback(null, "No bulk IN endpoint on this USB device — cannot receive a response")
            return
        }
        ioExecutor.execute {
            try {
                val buffer = ByteArray(inEp.maxPacketSize.coerceAtLeast(64))
                val read = conn.bulkTransfer(inEp, buffer, buffer.size, timeoutMs)
                if (read >= 0) {
                    callback(buffer.copyOf(read), null)
                } else {
                    callback(null, "USB read timed out or failed")
                }
            } catch (e: Exception) {
                Log.i(TAG, "USB read threw: ${e.message}")
                callback(null, "USB read timed out or failed")
            }
        }
    }
}
