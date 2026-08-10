package com.nipuna.esp32controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Renders a 128x64 1-bit SSD1306-style framebuffer (1024 bytes, page-addressed
 * exactly like the real chip: 8 pages of 128 columns, LSB = top pixel of the page).
 */
class OledView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val onPaint = Paint().apply {
        color = Color.parseColor("#00E5C7")
        isAntiAlias = false
    }
    private val offPaint = Paint().apply {
        color = Color.parseColor("#020403")
        isAntiAlias = false
    }

    private var framebuffer: ByteArray? = null

    fun setFrame(bytes: ByteArray) {
        framebuffer = bytes
        invalidate()
    }

    fun clear() {
        framebuffer = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, offPaint)

        val fb = framebuffer ?: return
        val cellW = w / Protocol.OLED_WIDTH
        val cellH = h / Protocol.OLED_HEIGHT

        for (page in 0 until 8) {
            for (col in 0 until Protocol.OLED_WIDTH) {
                val byteIndex = page * Protocol.OLED_WIDTH + col
                if (byteIndex >= fb.size) continue
                val byteVal = fb[byteIndex].toInt()
                for (bit in 0 until 8) {
                    val on = (byteVal shr bit) and 0x01 == 1
                    if (!on) continue
                    val y = page * 8 + bit
                    val left = col * cellW
                    val top = y * cellH
                    canvas.drawRect(left, top, left + cellW, top + cellH, onPaint)
                }
            }
        }
    }
}
