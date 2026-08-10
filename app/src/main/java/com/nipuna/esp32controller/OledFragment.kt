package com.nipuna.esp32controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class OledFragment : Fragment(R.layout.fragment_oled) {

    private lateinit var oledView: OledView
    private lateinit var meta: TextView
    private var frameCount = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        oledView = view.findViewById(R.id.oledView)
        meta = view.findViewById(R.id.oledMeta)
        view.findViewById<View>(R.id.btnClearOled).setOnClickListener {
            oledView.clear()
            frameCount = 0
            meta.text = "128 x 64 · cleared"
        }
    }

    fun renderFrame(bytes: ByteArray) {
        if (!isAdded) return
        oledView.setFrame(bytes)
        frameCount++
        meta.text = "128 x 64 · frame #$frameCount"
    }
}
