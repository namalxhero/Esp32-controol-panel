package com.nipuna.esp32controller

/**
 * Common capability interface implemented by both UsbSerialManager and
 * BleSerialManager, so MainActivity can hold either behind one reference
 * (see MainActivity.activeManager()).
 */
interface Transport {
    fun send(command: String)
    fun isConnected(): Boolean
    fun disconnect()
}
