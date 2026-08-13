package com.nipuna.esp32controller

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.zip.CRC32
import kotlin.experimental.xor

/**
 * Minimal reimplementation of Espressif's esptool serial protocol
 * (SLIP framing + ROM loader commands) so the app can flash a raw
 * MicroPython .bin directly over the existing USB-serial connection,
 * with no PC / Termux / esptool.py involved.
 *
 * Must be called BEFORE UsbSerialManager takes over the port (flashing
 * needs exclusive raw access at bootloader baud rates).
 */
class EspToolManager(private val port: UsbSerialPort) {

    companion object {
        private const val SLIP_END = 0xC0
        private const val SLIP_ESC = 0xDB
        private const val SLIP_ESC_END = 0xDC
        private const val SLIP_ESC_ESC = 0xDD

        // ROM loader command IDs
        private const val ESP_SYNC = 0x08
        private const val ESP_SPI_ATTACH = 0x0D
        private const val ESP_FLASH_BEGIN = 0x02
        private const val ESP_FLASH_DATA = 0x03
        private const val ESP_FLASH_END = 0x04
        private const val ESP_READ_REG = 0x0A

        private const val FLASH_BLOCK_SIZE = 0x400 // 1024 bytes per chunk (ROM loader, no stub)
        private const val FLASH_WRITE_ADDR = 0x1000 // typical MicroPython flash offset (ESP32-S3 usually 0x0)

        private const val SYNC_TIMEOUT_MS = 100
        private const val CMD_TIMEOUT_MS = 3000
    }

    interface ProgressListener {
        fun onStatus(message: String)
        fun onProgress(percent: Int)
        fun onDone(success: Boolean, message: String)
    }

    var listener: ProgressListener? = null
    private var sequence = 0

    // ---------------- Public entry point ----------------

    /**
     * Resets the ESP32 into ROM bootloader mode via DTR/RTS, syncs,
     * then writes [firmwareBytes] to flash starting at [flashOffset].
     * Runs synchronously — call from a background thread.
     */
    fun flash(firmwareBytes: ByteArray, flashOffset: Int = FLASH_WRITE_ADDR) {
        try {
            listener?.onStatus("Resetting into bootloader…")
            enterBootloader()

            listener?.onStatus("Syncing…")
            if (!sync()) {
                listener?.onDone(false, "Sync failed — hold BOOT button and retry")
                return
            }

            listener?.onStatus("Attaching SPI flash…")
            spiAttach()

            val blockCount = (firmwareBytes.size + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE
            listener?.onStatus("Erasing / beginning flash write…")
            flashBegin(firmwareBytes.size, blockCount, flashOffset)

            for (i in 0 until blockCount) {
                val start = i * FLASH_BLOCK_SIZE
                val end = minOf(start + FLASH_BLOCK_SIZE, firmwareBytes.size)
                var chunk = firmwareBytes.copyOfRange(start, end)
                if (chunk.size < FLASH_BLOCK_SIZE) {
                    // pad final block with 0xFF (erased-flash value)
                    chunk = chunk + ByteArray(FLASH_BLOCK_SIZE - chunk.size) { 0xFF.toByte() }
                }
                flashData(chunk, i)
                listener?.onProgress(((i + 1) * 100) / blockCount)
            }

            listener?.onStatus("Finalizing…")
            flashEnd(reboot = true)

            listener?.onDone(true, "Flash complete — ESP32 rebooting")
        } catch (e: Exception) {
            listener?.onDone(false, "Flash failed: ${e.message}")
        }
    }

    // ---------------- Bootloader entry (classic esptool reset sequence) ----------------

    private fun enterBootloader() {
        // Classic "DTR = EN (reset), RTS = GPIO0 (boot select)" toggle sequence.
        // Wiring assumption: standard ESP32-S3 dev board with auto-reset circuit
        // (most dev-kit boards with a USB-serial chip wire this automatically).
        port.dtr = false
        port.rts = true
        Thread.sleep(100)
        port.dtr = true
        port.rts = false
        Thread.sleep(100)
        port.dtr = false
        Thread.sleep(50)
        flushInput()
    }

    private fun flushInput() {
        try {
            val buf = ByteArray(256)
            while (port.read(buf, 20) > 0) { /* discard */ }
        } catch (_: IOException) {
        }
    }

    // ---------------- SLIP framing ----------------

    private fun slipEncode(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(SLIP_END)
        for (b in data) {
            val ub = b.toInt() and 0xFF
            when (ub) {
                SLIP_END -> { out.write(SLIP_ESC); out.write(SLIP_ESC_END) }
                SLIP_ESC -> { out.write(SLIP_ESC); out.write(SLIP_ESC_ESC) }
                else -> out.write(ub)
            }
        }
        out.write(SLIP_END)
        return out.toByteArray()
    }

    /** Reads and de-SLIPs exactly one framed packet, or throws on timeout. */
    private fun slipReadPacket(timeoutMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        val out = java.io.ByteArrayOutputStream()
        var started = false
        val single = ByteArray(1)

        while (System.currentTimeMillis() < deadline) {
            val n = port.read(single, 50)
            if (n <= 0) continue
            val ub = single[0].toInt() and 0xFF

            if (!started) {
                if (ub == SLIP_END) started = true
                continue
            }
            if (ub == SLIP_END) {
                if (out.size() > 0) return out.toByteArray()
                continue // leading END of next frame
            }
            if (ub == SLIP_ESC) {
                val esc = ByteArray(1)
                if (port.read(esc, 50) <= 0) throw IOException("SLIP escape read timeout")
                val eb = esc[0].toInt() and 0xFF
                out.write(if (eb == SLIP_ESC_END) SLIP_END else SLIP_ESC)
            } else {
                out.write(ub)
            }
        }
        throw IOException("Timed out waiting for response")
    }

    // ---------------- Command framing ----------------

    private fun checksum(data: ByteArray): Int {
        var cs = 0xEF
        for (b in data) cs = cs xor (b.toInt() and 0xFF)
        return cs
    }

    private fun sendCommand(op: Int, data: ByteArray, checksumOverData: ByteArray? = null): ByteArray {
        val cs = checksum(checksumOverData ?: ByteArray(0))
        val header = ByteArray(8)
        header[0] = 0x00
        header[1] = op.toByte()
        header[2] = (data.size and 0xFF).toByte()
        header[3] = ((data.size shr 8) and 0xFF).toByte()
        header[4] = (cs and 0xFF).toByte()
        header[5] = 0; header[6] = 0; header[7] = 0

        val packet = header + data
        val framed = slipEncode(packet)
        port.write(framed, CMD_TIMEOUT_MS)

        return readResponse(op)
    }

    /** Reads response packets until it finds one matching [expectedOp], or throws. */
    private fun readResponse(expectedOp: Int): ByteArray {
        val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = slipReadPacket(CMD_TIMEOUT_MS)
            if (pkt.size < 8) continue
            val respOp = pkt[1].toInt() and 0xFF
            if (respOp != expectedOp) continue
            val body = pkt.copyOfRange(8, pkt.size)
            // Last 4 bytes of body are the status (2 bytes status/error under ROM loader)
            if (body.size >= 2) {
                val status = body[body.size - 2].toInt() and 0xFF
                if (status != 0) {
                    val err = body[body.size - 1].toInt() and 0xFF
                    throw IOException("ROM loader error, status=$status err=$err")
                }
            }
            return body
        }
        throw IOException("No response for command 0x${expectedOp.toString(16)}")
    }

    // ---------------- Individual commands ----------------

    private fun sync(): Boolean {
        val syncPayload = ByteArray(36).also {
            it[0] = 0x07; it[1] = 0x07; it[2] = 0x12; it[3] = 0x20
            for (i in 4 until 36) it[i] = 0x55.toByte()
        }
        repeat(5) { attempt ->
            try {
                sendCommand(ESP_SYNC, syncPayload)
                // Drain any extra sync-response frames the ROM sends
                Thread.sleep(50)
                flushInput()
                return true
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        return false
    }

    private fun spiAttach() {
        val data = ByteArray(8) // all-zero SPI pin config = use default pins
        sendCommand(ESP_SPI_ATTACH, data, data)
    }

    private fun flashBegin(sizeBytes: Int, numBlocks: Int, offset: Int) {
        val data = ByteArray(16)
        writeLE(data, 0, sizeBytes)
        writeLE(data, 4, numBlocks)
        writeLE(data, 8, FLASH_BLOCK_SIZE)
        writeLE(data, 12, offset)
        sendCommand(ESP_FLASH_BEGIN, data, data)
    }

    private fun flashData(chunk: ByteArray, seqNum: Int) {
        val header = ByteArray(16)
        writeLE(header, 0, chunk.size)
        writeLE(header, 4, seqNum)
        writeLE(header, 8, 0)
        writeLE(header, 12, 0)
        val payload = header + chunk
        sendCommand(ESP_FLASH_DATA, payload, chunk)
        sequence++
    }

    private fun flashEnd(reboot: Boolean) {
        val data = ByteArray(4)
        writeLE(data, 0, if (reboot) 0 else 1) // 0 = reboot into new firmware
        sendCommand(ESP_FLASH_END, data, data)
    }

    private fun writeLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
