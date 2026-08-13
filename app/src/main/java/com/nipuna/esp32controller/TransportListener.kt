package com.nipuna.esp32controller

/**
 * Shared callback interface for both USB-serial and BLE transports,
 * so MainActivity doesn't need to care which one is active.
 */
interface TransportListener {
    fun onConnected(deviceName: String)
    fun onDisconnected()
    fun onLineReceived(line: String)
    fun onError(message: String)
}
