package com.nipuna.esp32controller

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity(), TransportListener {

    private lateinit var usbManager: UsbSerialManager
    private lateinit var bleManager: BleSerialManager
    private lateinit var viewPager: ViewPager2
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var transportToggle: MaterialButtonToggleGroup
    private lateinit var pagerAdapter: ViewPagerAdapter

    private lateinit var codeModeToggle: MaterialButtonToggleGroup
    private lateinit var codeEditor: EditText
    private lateinit var btnRunCode: MaterialButton
    private lateinit var btnFlashMicroPython: MaterialButton

    /** true = BLE selected, false = USB selected (default) */
    private var useBle = false
    /** true = C++ cloud-build mode, false = MicroPython instant-run mode (default) */
    private var useCpp = false

    private val microPython by lazy {
        MicroPythonRunner { raw -> activeManager().send(raw) }
    }

    private val cloudBuildClient by lazy {
        CloudBuildClient(
            githubToken = getGithubToken(),
            repoOwner = "namalxhero",
            repoName = "Esp32-controol-panel",
            onStatus = { msg -> runOnUiThread { statusText.text = msg } }
        )
    }

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                bleManager.connect()
            } else {
                statusText.text = "Bluetooth permission denied"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnConnect = findViewById(R.id.btnConnect)
        transportToggle = findViewById(R.id.transportToggle)
        viewPager = findViewById(R.id.viewPager)

        codeModeToggle = findViewById(R.id.codeModeToggle)
        codeEditor = findViewById(R.id.codeEditorInput)
        btnRunCode = findViewById(R.id.btnRunCode)
        btnFlashMicroPython = findViewById(R.id.btnFlashMicroPython)

        pagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        val tabLayout: TabLayout = findViewById(R.id.tabLayout)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.tab_oled) else getString(R.string.tab_terminal)
        }.attach()

        usbManager = UsbSerialManager(this)
        usbManager.listener = this
        usbManager.registerReceiver()

        bleManager = BleSerialManager(this)
        bleManager.listener = this

        transportToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useBle = checkedId == R.id.btnTransportBle
            if (activeManager().isConnected()) {
                usbManager.disconnect()
                bleManager.disconnect()
            }
        }

        codeModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useCpp = checkedId == R.id.btnModeCpp
            btnRunCode.text = if (useCpp) "Compile & Flash" else "Run"
        }

        btnConnect.setOnClickListener {
            if (activeManager().isConnected()) {
                activeManager().disconnect()
            } else if (useBle) {
                requestBleThenConnect()
            } else {
                statusText.text = "Requesting USB permission…"
                usbManager.connect()
            }
        }

        btnRunCode.setOnClickListener {
            val code = codeEditor.text.toString()
            if (code.isBlank()) {
                statusText.text = "Nothing to run"
                return@setOnClickListener
            }
            if (useCpp) {
                statusText.text = "Starting cloud build…"
                Thread {
                    cloudBuildClient.compileAndFlash(code, esp32IpAddress = getSavedEsp32Ip())
                }.start()
            } else {
                if (!activeManager().isConnected()) {
                    statusText.text = "Connect to ESP32 first"
                    return@setOnClickListener
                }
                microPython.run(code)
            }
        }

        btnFlashMicroPython.setOnClickListener {
            flashMicroPython()
        }

        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            useBle = false
            usbManager.connect()
        }
    }

    private fun activeManager(): Transport = if (useBle) bleManager else usbManager

    private fun requestBleThenConnect() {
        if (bleManager.hasBlePermissions()) {
            bleManager.connect()
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        blePermissionLauncher.launch(perms)
    }

    fun sendCommand(command: String) {
        activeManager().send(command)
    }

    // ---- MicroPython bootstrap flashing (one-time, over USB) ----

    private fun flashMicroPython() {
        val usbSystemManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbSystemManager)
            .firstOrNull()

        if (driver == null) {
            statusText.text = "No USB device found — connect ESP32 via OTG first"
            return
        }

        usbManager.disconnect() // release the port so EspToolManager gets exclusive access

        Thread {
            try {
                val connection = usbSystemManager.openDevice(driver.device)
                val port = driver.ports[0]
                port.open(connection)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                val binBytes = assets.open("micropython_esp32s3.bin").readBytes()

                val flasher = EspToolManager(port)
                flasher.listener = object : EspToolManager.ProgressListener {
                    override fun onStatus(message: String) {
                        runOnUiThread { statusText.text = message }
                    }
                    override fun onProgress(percent: Int) {
                        runOnUiThread { statusText.text = "Flashing… $percent%" }
                    }
                    override fun onDone(success: Boolean, message: String) {
                        runOnUiThread { statusText.text = message }
                        port.close()
                    }
                }
                flasher.flash(binBytes)
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Flash error: ${e.message}" }
            }
        }.start()
    }

    // ---- Settings placeholders — wire these to a real settings screen / EncryptedSharedPreferences ----

    private fun getGithubToken(): String =
        getSharedPreferences("secure_settings", Context.MODE_PRIVATE).getString("github_token", "") ?: ""

    private fun getSavedEsp32Ip(): String =
        getSharedPreferences("secure_settings", Context.MODE_PRIVATE).getString("esp32_ip", "") ?: ""

    private fun oledFragment(): OledFragment? =
        supportFragmentManager.fragments.filterIsInstance<OledFragment>().firstOrNull()

    private fun terminalFragment(): TerminalFragment? =
        supportFragmentManager.fragments.filterIsInstance<TerminalFragment>().firstOrNull()

    // ---- TransportListener callbacks (may arrive on a background thread) ----

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
        bleManager.disconnect()
    }
}
