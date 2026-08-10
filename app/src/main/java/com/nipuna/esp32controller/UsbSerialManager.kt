package com.nipuna.esp32controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Wraps usb-serial-for-android to give a small connect/send/receive API,
 * driven purely by OTG USB (no Bluetooth / Wi-Fi involved).
 */
class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.nipuna.esp32controller.USB_PERMISSION"
        const val BAUD_RATE = 115200
    }

    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onLineReceived(line: String)
        fun onError(message: String)
    }

    var listener: Listener? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val lineBuffer = StringBuilder()

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            synchronized(this) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    listener?.onError("USB permission denied")
                }
            }
        }
    }

    private var receiverRegistered = false

    fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
    }

    /** Finds the first available ESP32-like USB-serial device and requests permission / connects. */
    fun connect() {
        val availableDrivers: List<UsbSerialDriver> =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            listener?.onError("No USB serial device found. Connect ESP32 via OTG.")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            openDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: run {
                listener?.onError("Unsupported USB device")
                return
            }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            listener?.onError("Could not open USB connection (permission?)")
            return
        }

        val serialPort = driver.ports[0]
        try {
            serialPort.open(connection)
            serialPort.setParameters(
                BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
        } catch (e: IOException) {
            listener?.onError("Failed to open port: ${e.message}")
            return
        }

        port = serialPort
        startIoManager()
        listener?.onConnected(driver.device.deviceName ?: "ESP32")
    }

    private fun startIoManager() {
        val p = port ?: return
        ioManager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                handleIncoming(data)
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "IO error", e)
                listener?.onError("Connection lost: ${e.message}")
                disconnect()
            }
        })
        executor.submit(ioManager)
    }

    private fun handleIncoming(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        for (ch in text) {
            if (ch == '\n') {
                val line = lineBuffer.toString()
                lineBuffer.clear()
                if (line.isNotEmpty()) listener?.onLineReceived(line)
            } else if (ch != '\r') {
                lineBuffer.append(ch)
            }
        }
    }

    fun send(command: String) {
        val p = port
        if (p == null) {
            listener?.onError("Not connected")
            return
        }
        try {
            val payload = (command + "\n").toByteArray(Charsets.UTF_8)
            p.write(payload, 500)
        } catch (e: IOException) {
            listener?.onError("Send failed: ${e.message}")
        }
    }

    fun isConnected(): Boolean = port != null

    fun disconnect() {
        try {
            ioManager?.stop()
            port?.close()
        } catch (_: IOException) {
        }
        ioManager = null
        port = null
        listener?.onDisconnected()
    }
}
