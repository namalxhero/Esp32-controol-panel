package com.nipuna.esp32controller

import android.util.Base64

/**
 * Simple line-based text protocol spoken over the USB-serial link.
 * Every line the ESP32 sends is one of:
 *
 *   #OLED:<base64 of 1024 bytes>   -> a full 128x64 1-bit SSD1306 framebuffer
 *   #CMDS:cmd1,cmd2,cmd3           -> list of commands this firmware supports
 *                                      (app renders these as tappable chips)
 *   anything else                  -> plain text, shown in the terminal log
 *
 * See esp32-firmware/esp32_oled_controller.ino for the matching Arduino side.
 */
object Protocol {

    private const val OLED_PREFIX = "#OLED:"
    private const val CMDS_PREFIX = "#CMDS:"
    const val OLED_WIDTH = 128
    const val OLED_HEIGHT = 64
    const val OLED_BYTES = OLED_WIDTH * OLED_HEIGHT / 8 // 1024

    sealed class ParsedLine {
        data class OledFrame(val bytes: ByteArray) : ParsedLine()
        data class CommandList(val commands: List<String>) : ParsedLine()
        data class LogText(val text: String) : ParsedLine()
    }

    fun parse(rawLine: String): ParsedLine {
        val line = rawLine.trimEnd('\r', '\n')
        return when {
            line.startsWith(OLED_PREFIX) -> {
                val b64 = line.removePrefix(OLED_PREFIX)
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    if (bytes.size == OLED_BYTES) {
                        ParsedLine.OledFrame(bytes)
                    } else {
                        ParsedLine.LogText(line)
                    }
                } catch (e: IllegalArgumentException) {
                    ParsedLine.LogText(line)
                }
            }
            line.startsWith(CMDS_PREFIX) -> {
                val cmds = line.removePrefix(CMDS_PREFIX)
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                ParsedLine.CommandList(cmds)
            }
            else -> ParsedLine.LogText(line)
        }
    }
}
