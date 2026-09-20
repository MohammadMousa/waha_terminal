package com.waha.waha_terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Receives the raw accessory streams; framing/codec lives on the other side of this seam. */
interface AccessoryStreamListener {
    fun onStreamsOpened(input: InputStream, output: OutputStream)
    /** Called exactly once per opened link, after both streams are already closed. */
    fun onStreamsClosed(reason: String)
}

/**
 * USB DEVICE side of the Waha link: the kiosk is the host and switches this
 * phone into Android Open Accessory mode; this class opens the accessory and
 * hands the byte streams to [AccessoryStreamListener]. Generic Android
 * platform APIs only — no vendor SDK, no Geidea/PAX code.
 *
 * Connection state is never a cached boolean: [isOpen] re-derives from the
 * live accessory list plus whether the descriptor is still held, and a
 * detach broadcast or any stream failure tears everything down at once.
 * The permission result is likewise re-read from [UsbManager.hasPermission]
 * rather than trusted from broadcast extras.
 */
class UsbAccessoryManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onEvent: (state: String, detail: String?) -> Unit,
    private val listener: AccessoryStreamListener,
) {
    companion object {
        private const val TAG = "WahaTerminal"
        private const val ACTION_PERMISSION = "com.waha.waha_terminal.USB_ACCESSORY_PERMISSION"
    }

    private var accessory: UsbAccessory? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var registered = false
    private val lock = Any()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            val acc = currentAttached()
            if (acc != null && usbManager.hasPermission(acc)) {
                Log.i(TAG, "USB permission granted")
                onEvent("permissionGranted", null)
                open(acc)
            } else {
                Log.i(TAG, "USB permission denied")
                onEvent("error", "PERMISSION_DENIED")
            }
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
            Log.i(TAG, "USB disconnected")
            teardown("accessory detached")
            onEvent("disconnected", "accessory detached")
        }
    }

    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            context, permissionReceiver, IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context, detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        try { context.unregisterReceiver(detachReceiver) } catch (_: IllegalArgumentException) {}
        registered = false
        teardown("manager stopped")
    }

    /** Entry point for the system launching us on USB_ACCESSORY_ATTACHED. */
    fun handleAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        Log.i(TAG, "USB device detected")
        onEvent("deviceAttached", null)
        connect()
    }

    /** Opens the attached accessory, asking for permission first if needed. */
    fun connect() {
        try {
            val acc = currentAttached()
            if (acc == null) {
                // Over a C-to-C cable between two phones the roles are
                // negotiated and can land backwards. If we can see a USB
                // device, we are the host — the link can never form until the
                // roles are swapped (or a USB-A end is used on the host side).
                if (usbManager.deviceList.isNotEmpty()) {
                    Log.i(TAG, "USB role mismatch: this device is acting as host")
                    onEvent("roleMismatch", "this device is acting as USB host")
                } else {
                    onEvent("error", "NOT_CONNECTED")
                }
                return
            }
            if (usbManager.hasPermission(acc)) {
                open(acc)
                return
            }
            onEvent("permissionRequested", null)
            // Explicit intent (package set) + immutable: nothing in the
            // result is read from extras, see permissionReceiver.
            val permissionIntent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, 0, permissionIntent, PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(acc, pending)
        } catch (e: Exception) {
            Log.i(TAG, "USB connect failed: ${e.javaClass.simpleName}")
            onEvent("error", "NOT_CONNECTED")
        }
    }

    private fun open(acc: UsbAccessory) {
        // Attach intent, permission broadcast and a Dart-side connect() can all
        // race to here; re-opening a live link would close it under the peer
        // and discard the hello already written.
        if (synchronized(lock) { accessory == acc && descriptor != null } && isOpen()) {
            Log.i(TAG, "USB accessory already open")
            return
        }
        try {
            teardown("reopening")
            val pfd = usbManager.openAccessory(acc)
            if (pfd == null) {
                onEvent("error", "NOT_CONNECTED")
                return
            }
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            synchronized(lock) {
                accessory = acc
                descriptor = pfd
                input = inStream
                output = outStream
            }
            Log.i(TAG, "USB connection opened")
            onEvent("connected", null)
            listener.onStreamsOpened(inStream, outStream)
        } catch (e: Exception) {
            Log.i(TAG, "USB open failed: ${e.javaClass.simpleName}")
            teardown("open failed")
            onEvent("error", "NOT_CONNECTED")
        }
    }

    /** Live: the accessory must still be enumerated AND we must still hold the descriptor. */
    fun isOpen(): Boolean {
        val acc = synchronized(lock) { accessory } ?: return false
        val stillAttached = usbManager.accessoryList?.any { it == acc } ?: false
        return stillAttached && synchronized(lock) { descriptor != null }
    }

    /** Live platform state: an accessory is enumerated (we are the device). */
    fun hasAttachedAccessory(): Boolean = currentAttached() != null

    /** Live platform state: a USB device is visible, i.e. this phone is the host. */
    fun actingAsHost(): Boolean = try { usbManager.deviceList.isNotEmpty() } catch (_: Exception) { false }

    fun disconnect() {
        teardown("disconnected by app")
        Log.i(TAG, "USB connection closed")
        onEvent("disconnected", "disconnected by app")
    }

    /** Idempotent. Closes streams in try/catch, nulls every reference, notifies listener once. */
    fun teardown(reason: String) {
        val hadLink: Boolean
        val inStream: InputStream?
        val outStream: OutputStream?
        val pfd: ParcelFileDescriptor?
        synchronized(lock) {
            hadLink = descriptor != null
            inStream = input
            outStream = output
            pfd = descriptor
            input = null
            output = null
            descriptor = null
            accessory = null
        }
        try { inStream?.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        if (hadLink) listener.onStreamsClosed(reason)
    }

    private fun currentAttached(): UsbAccessory? = usbManager.accessoryList?.firstOrNull()
}
