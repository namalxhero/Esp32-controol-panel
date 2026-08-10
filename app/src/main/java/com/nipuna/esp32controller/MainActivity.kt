package com.nipuna.esp32controller

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity(), UsbSerialManager.Listener {

    private lateinit var usbManager: UsbSerialManager
    private lateinit var viewPager: ViewPager2
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var pagerAdapter: ViewPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnConnect = findViewById(R.id.btnConnect)
        viewPager = findViewById(R.id.viewPager)

        pagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        val tabLayout: TabLayout = findViewById(R.id.tabLayout)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.tab_oled) else getString(R.string.tab_terminal)
        }.attach()

        usbManager = UsbSerialManager(this)
        usbManager.listener = this
        usbManager.registerReceiver()

        btnConnect.setOnClickListener {
            if (usbManager.isConnected()) {
                usbManager.disconnect()
            } else {
                statusText.text = "Requesting USB permission…"
                usbManager.connect()
            }
        }

        // If launched via USB_DEVICE_ATTACHED intent-filter, try connecting right away.
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            usbManager.connect()
        }
    }

    fun sendCommand(command: String) {
        usbManager.send(command)
    }

    private fun oledFragment(): OledFragment? =
        supportFragmentManager.fragments.filterIsInstance<OledFragment>().firstOrNull()

    private fun terminalFragment(): TerminalFragment? =
        supportFragmentManager.fragments.filterIsInstance<TerminalFragment>().firstOrNull()

    // ---- UsbSerialManager.Listener callbacks (arrive on the IO thread) ----

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            statusDot.setBackgroundResource(R.drawable.dot_connected)
            statusText.text = "Connected · $deviceName"
            btnConnect.text = getString(R.string.disconnect)
            terminalFragment()?.appendLog("Connected to $deviceName", outgoing = false)
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            statusDot.setBackgroundResource(R.drawable.dot_disconnected)
            statusText.text = getString(R.string.not_connected)
            btnConnect.text = getString(R.string.connect)
            terminalFragment()?.appendLog("Disconnected", outgoing = false)
        }
    }

    override fun onLineReceived(line: String) {
        when (val parsed = Protocol.parse(line)) {
            is Protocol.ParsedLine.OledFrame -> runOnUiThread {
                oledFragment()?.renderFrame(parsed.bytes)
            }
            is Protocol.ParsedLine.CommandList -> runOnUiThread {
                terminalFragment()?.updateCommands(parsed.commands)
                terminalFragment()?.appendLog("Commands updated: ${parsed.commands.joinToString()}", outgoing = false)
            }
            is Protocol.ParsedLine.LogText -> runOnUiThread {
                terminalFragment()?.appendLog(parsed.text, outgoing = false)
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            statusText.text = message
            terminalFragment()?.appendLog("ERROR: $message", outgoing = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.disconnect()
        usbManager.unregisterReceiver()
    }
}
