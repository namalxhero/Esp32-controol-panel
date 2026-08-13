package com.nipuna.esp32controller

/**
 * Sends Python source to an ESP32 running MicroPython using the
 * "raw REPL" protocol, over whichever transport (USB or BLE) is active.
 * Requires MicroPython firmware already flashed on the ESP32 (one-time,
 * via esptool from a PC/Termux — cannot be done from this app).
 */
class MicroPythonRunner(private val sendRaw: (String) -> Unit) {

    companion object {
        private const val CTRL_A = "\u0001" // enter raw REPL
        private const val CTRL_B = "\u0002" // exit raw REPL
        private const val CTRL_C = "\u0003" // interrupt running code
        private const val CTRL_D = "\u0004" // execute what's been sent
    }

    /** Interrupts whatever is running, then sends `code` to run immediately. */
    fun run(code: String) {
        sendRaw(CTRL_C)              // stop anything currently running
        sendRaw(CTRL_A)              // enter raw REPL mode
        sendRaw(code)                // the actual script
        sendRaw(CTRL_D)              // execute
    }

    fun stop() {
        sendRaw(CTRL_C)
        sendRaw(CTRL_B) // back to friendly REPL
    }
}
