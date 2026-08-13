package com.nipuna.esp32controller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLE transport using a Nordic UART Service (NUS)-style GATT profile.
 * ESP32 firmware side needs a matching NimBLE UART service with the
 * same three UUIDs to talk to this.
 */
class BleSerialManager(private val context: Context) : Transport {

    companion object {
        private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // app -> ESP32
        private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // ESP32 -> app
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val SCAN_TIMEOUT_MS = 10_000L
    }

    var listener: TransportListener? = null

    private val bluetoothManager: BluetoothManager
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lineBuffer = StringBuilder()
    private var scanning = false

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun connect() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            listener?.onError("Bluetooth is off or not available")
            return
        }
        if (!hasBlePermissions()) {
            listener?.onError("Bluetooth permission not granted")
            return
        }

        scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            listener?.onError("BLE scanning not supported")
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        try {
            scanner?.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            listener?.onError("Missing BLE permission: ${e.message}")
            scanning = false
            return
        }

        mainHandler.postDelayed({
            if (scanning) {
                stopScan()
                listener?.onError("No ESP32 found via BLE (scan timed out)")
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onError("BLE scan failed (code $errorCode)")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            listener?.onError("Missing BLE connect permission: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.discoverServices()
                } catch (e: SecurityException) {
                    listener?.onError("Missing BLE permission: ${e.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxChar = null
                listener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("BLE service discovery failed")
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                listener?.onError("ESP32 UART service not found")
                return
            }
            rxChar = service.getCharacteristic(RX_CHAR_UUID)
            val txChar = service.getCharacteristic(TX_CHAR_UUID)

            if (rxChar == null || txChar == null) {
                listener?.onError("ESP32 UART characteristics not found")
                return
            }

            try {
                g.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            } catch (e: SecurityException) {
                listener?.onError("Missing BLE permission: ${e.message}")
                return
            }

            listener?.onConnected(g.device.name ?: "ESP32 (BLE)")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) handleIncoming(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == TX_CHAR_UUID) {
                handleIncoming(characteristic.value)
            }
        }
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

    override fun send(command: String) {
        val g = gatt
        val c = rxChar
        if (g == null || c == null) {
            listener?.onError("Not connected")
            return
        }
        val payload = (command + "\n").toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: SecurityException) {
            listener?.onError("Missing BLE permission: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = rxChar != null

    override fun disconnect() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        rxChar = null
        listener?.onDisconnected()
    }
}
