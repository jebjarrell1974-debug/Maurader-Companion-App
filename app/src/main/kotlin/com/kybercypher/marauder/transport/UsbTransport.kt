package com.kybercypher.marauder.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object NoDevice : ConnectionState
    data class NeedsPermission(val device: UsbDevice) : ConnectionState
    data class Connecting(val device: UsbDevice) : ConnectionState
    data class Connected(val device: UsbDevice, val driverName: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/**
 * USB serial transport for the Marauder.
 *
 * Every non-obvious choice here is deliberate and sourced:
 *
 *  - readTimeout 0. The library's own source warns that bulkTransfer "will cause
 *    data loss with short timeout + high baud rates + continuous transfer", and
 *    that requestWait(timeout) is worse (it can SIGSEGV). Loss was observed at
 *    timeouts up to 200 ms and shows up as small fragments missing from the
 *    middle of the data — i.e. silent capture corruption. readQueue also
 *    REQUIRES timeout 0 and throws otherwise.
 *
 *  - readBufferSize == the endpoint's max packet size (64 on full speed). A
 *    SMALLER buffer makes read() return empty, which is indistinguishable from a
 *    timeout or a genuine error.
 *
 *  - readQueue(8). Added in 3.10.0 precisely for sustained high-rate reads, so
 *    the endpoint is never left unserviced.
 *
 *  - DTR and RTS are NEVER asserted. On this board they reset the chip: the
 *    Marauder v8 flashes over this port, so the auto-reset circuit is fitted,
 *    and on native-USB ESP32 parts the peripheral resets on an RTS transition
 *    internally. The library's Cp21xxSerialPort already writes the correct
 *    DTR_DISABLE|RTS_DISABLE state on open — do not "fix" it.
 *
 *  - PendingIntent.FLAG_MUTABLE, not FLAG_IMMUTABLE. requestPermission() supplies
 *    EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED as fill-in extras; with
 *    FLAG_IMMUTABLE they are dropped, the device arrives null, and the grant
 *    reads false no matter what the user tapped — silently. Google's own USB
 *    sample is wrong about this. hasPermission() is treated as authoritative.
 *
 * onNewData runs on the library's read thread, so it does nothing but hand the
 * bytes off. All parsing happens elsewhere.
 */
class UsbTransport(private val context: Context) {

    companion object {
        private const val ACTION_GRANT_USB = "com.kybercypher.marauder.GRANT_USB"
        const val BAUD = 115200
        private const val READ_QUEUE_DEPTH = 8
        private const val WRITE_TIMEOUT_MS = 2000
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state = _state.asStateFlow()

    /**
     * Raw bytes off the wire. Generous buffer and DROP_OLDEST: if the consumer
     * ever falls behind we would rather lose the oldest bytes loudly than block
     * the read thread, which would make the firmware drop packets instead.
     */
    private val _bytes = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bytes = _bytes.asSharedFlow()

    @Volatile var droppedChunks = 0L; private set

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_GRANT_USB) return
            val device = findDevice()
            // Authoritative check, rather than trusting the broadcast extra.
            if (device != null && usbManager.hasPermission(device)) {
                connect(device)
            } else {
                _state.value = ConnectionState.Failed("USB permission denied")
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, permissionReceiver, IntentFilter(ACTION_GRANT_USB),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(permissionReceiver) }
        receiverRegistered = false
    }

    fun findDevice(): UsbDevice? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()?.device

    /**
     * Entry point. Asks for permission if needed, otherwise connects.
     * Must be called before startForeground() with the connectedDevice type —
     * since Android 14 that type requires a UsbManager.requestPermission() call
     * and the system validates it at service creation.
     */
    fun requestConnect(): Boolean {
        val device = findDevice()
        if (device == null) {
            _state.value = ConnectionState.NoDevice
            return false
        }
        return if (usbManager.hasPermission(device)) {
            connect(device)
        } else {
            _state.value = ConnectionState.NeedsPermission(device)
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_GRANT_USB).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(device, pi)
            false
        }
    }

    private fun connect(device: UsbDevice): Boolean {
        _state.value = ConnectionState.Connecting(device)
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            _state.value = ConnectionState.Failed("No serial driver matched this device")
            return false
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            _state.value = ConnectionState.Failed("Could not open the USB device")
            return false
        }
        return try {
            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Deliberately NOT calling setDTR/setRTS. See the class comment.

            val manager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    // Read thread. Hand off and return. Nothing else, ever.
                    if (!_bytes.tryEmit(data)) droppedChunks++
                }

                override fun onRunError(e: Exception) {
                    _state.value = ConnectionState.Failed(e.message ?: "serial read error")
                    closeQuietly()
                }
            })
            manager.readTimeout = 0
            manager.readBufferSize = p.readEndpoint.maxPacketSize
            manager.setReadQueue(READ_QUEUE_DEPTH)
            // Thread priority intentionally left at the library default
            // (THREAD_PRIORITY_URGENT_AUDIO) — it exists to prevent data loss.
            manager.start()

            port = p
            io = manager
            _state.value = ConnectionState.Connected(device, driver.javaClass.simpleName)
            true
        } catch (e: IOException) {
            _state.value = ConnectionState.Failed(e.message ?: "open failed")
            closeQuietly()
            false
        }
    }

    fun write(line: String) {
        val p = port ?: return
        val payload = (line + "\n").toByteArray(Charsets.US_ASCII)
        runCatching { p.write(payload, WRITE_TIMEOUT_MS) }
    }

    /** Idempotent: both onRunError and ACTION_USB_DEVICE_DETACHED can fire for one yank. */
    fun disconnect() {
        closeQuietly()
        _state.value = ConnectionState.Disconnected
    }

    private fun closeQuietly() {
        // stop() does not interrupt a blocking read when readTimeout == 0, so the
        // port must be closed too. Drain first: on some Espressif parts closing
        // with data pending can wedge the endpoint until a power cycle.
        io?.let { runCatching { it.listener = null }; runCatching { it.stop() } }
        io = null
        port?.let { runCatching { it.close() } }
        port = null
    }
}
