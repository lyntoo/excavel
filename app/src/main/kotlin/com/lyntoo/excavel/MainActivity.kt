package com.lyntoo.excavel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import com.wit.witsdk.modular.sensor.device.exceptions.OpenDeviceException
import com.wit.witsdk.modular.sensor.example.ble5.Bwt901ble
import com.wit.witsdk.modular.sensor.example.ble5.interfaces.IBwt901bleRecordObserver
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.BluetoothBLE
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.BluetoothSPP
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.WitBluetoothManager
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.exceptions.BluetoothBLEException
import com.wit.witsdk.modular.sensor.modular.connector.modular.bluetooth.interfaces.IBluetoothFoundObserver
import com.wit.witsdk.modular.sensor.modular.processor.constant.WitSensorKey
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), IBluetoothFoundObserver, IBwt901bleRecordObserver {

    companion object {
        private const val PERM_REQUEST  = 101
        private const val REQUEST_BT_ON = 102
        private const val PREFS_NAME    = "excavel"
        private const val PREF_ORIENT   = "orientation"
        private const val PREF_AXIS     = "axis_index"

        // Beep starts at this deviation (degrees), silent beyond
        private const val BEEP_START_DEG = 15f
        // Slowest beep interval (ms) at BEEP_START_DEG
        private const val BEEP_MAX_MS    = 900L
        // Switch to continuous tone within this many degrees of 0
        private const val BEEP_CONT_DEG  = 0.4f
    }

    // BLE
    private val foundDevices  = mutableListOf<Bwt901ble>()
    private val deviceNames   = mutableListOf<String>()
    private var connectedDevice: Bwt901ble? = null
    private lateinit var deviceAdapter: ArrayAdapter<String>

    // Measurement — 0=Arm X+, 1=Arm X−, 2=Roll Y/Z
    private var axisIndex      = 0
    private var referenceAngle = 0f
    private var lastRawAngle   = 0f

    // Beep — -1=silent, 0=continuous, >0=interval ms
    private var toneGen: ToneGenerator? = null
    private val beepHandler = Handler(Looper.getMainLooper())
    private var beepInterval = -1L

    // Polling thread
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    // Prefs
    private lateinit var prefs: SharedPreferences

    // Views — scan
    private lateinit var viewFlipper:  ViewFlipper
    private lateinit var btnScan:      Button
    private lateinit var btnStopScan:  Button
    private lateinit var scanProgress: ProgressBar
    private lateinit var tvScanStatus: TextView
    private lateinit var lvDevices:    ListView

    // Views — level
    private lateinit var tvDeviceName:  TextView
    private lateinit var tvBattery:     TextView
    private lateinit var tvAngle:       TextView
    private lateinit var levelView:     LevelView
    private lateinit var spinnerAxis:   Spinner
    private lateinit var btnCalibrate:  Button
    private lateinit var cbBeep:        CheckBox
    private lateinit var btnDisconnect: Button
    private lateinit var btnRotate:     Button

    // ─── Lifecycle ───────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyOrientation(prefs.getString(PREF_ORIENT, "landscape") ?: "landscape")
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()
        setupAxisSpinner()
        setupListeners()
        updateRotateButton()
        try {
            WitBluetoothManager.requestPermissions(this)
            WitBluetoothManager.initInstance(this)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    // ─── Orientation ─────────────────────────────────────────────

    private fun applyOrientation(orient: String) {
        requestedOrientation = if (orient == "portrait")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun toggleOrientation() {
        val current = prefs.getString(PREF_ORIENT, "landscape") ?: "landscape"
        val next    = if (current == "landscape") "portrait" else "landscape"
        prefs.edit().putString(PREF_ORIENT, next).apply()
        applyOrientation(next)
        updateRotateButton()
    }

    private fun updateRotateButton() {
        val orient = prefs.getString(PREF_ORIENT, "landscape") ?: "landscape"
        btnRotate.text = if (orient == "landscape")
            getString(R.string.orient_portrait)
        else
            getString(R.string.orient_landscape)
    }

    // ─── Binding ─────────────────────────────────────────────────

    private fun bindViews() {
        viewFlipper   = findViewById(R.id.viewFlipper)
        btnScan       = findViewById(R.id.btnScan)
        btnStopScan   = findViewById(R.id.btnStopScan)
        scanProgress  = findViewById(R.id.scanProgress)
        tvScanStatus  = findViewById(R.id.tvScanStatus)
        lvDevices     = findViewById(R.id.lvDevices)
        tvDeviceName  = findViewById(R.id.tvDeviceName)
        tvBattery     = findViewById(R.id.tvBattery)
        tvAngle       = findViewById(R.id.tvAngle)
        levelView     = findViewById(R.id.levelView)
        spinnerAxis   = findViewById(R.id.spinnerAxis)
        btnCalibrate  = findViewById(R.id.btnCalibrate)
        cbBeep        = findViewById(R.id.cbBeep)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnRotate     = findViewById(R.id.btnRotate)
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        lvDevices.adapter = deviceAdapter
    }

    private fun setupAxisSpinner() {
        axisIndex = prefs.getInt(PREF_AXIS, 0)
        val labels = arrayOf(
            getString(R.string.axis_x_plus),
            getString(R.string.axis_x_minus),
            getString(R.string.axis_roll)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAxis.adapter = adapter
        spinnerAxis.setSelection(axisIndex)
        spinnerAxis.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                axisIndex = pos
                referenceAngle = 0f
                prefs.edit().putInt(PREF_AXIS, pos).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupListeners() {
        btnScan.setOnClickListener     { checkPrerequisitesAndScan() }
        btnStopScan.setOnClickListener { stopScan() }
        btnRotate.setOnClickListener   { toggleOrientation() }

        lvDevices.setOnItemClickListener { _, _, pos, _ ->
            if (pos < foundDevices.size) connectTo(foundDevices[pos])
        }

        btnCalibrate.setOnClickListener {
            referenceAngle = lastRawAngle
            Toast.makeText(
                this,
                getString(R.string.msg_calibrated, referenceAngle),
                Toast.LENGTH_SHORT
            ).show()
        }

        cbBeep.setOnCheckedChangeListener { _, checked ->
            if (!checked) stopBeep()
        }

        btnDisconnect.setOnClickListener { disconnect() }
    }

    // ─── Pre-scan checks ─────────────────────────────────────────

    private fun checkPrerequisitesAndScan() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            tvScanStatus.text = getString(R.string.msg_bt_enabling)
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_BT_ON)
            return
        }
        val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locManager)) {
            tvScanStatus.text = getString(R.string.msg_location_required)
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        requestPermsAndScan()
    }

    @Deprecated("Needed for BT enable result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BT_ON) {
            if (resultCode == RESULT_OK) checkPrerequisitesAndScan()
            else tvScanStatus.text = getString(R.string.msg_bt_denied)
        }
    }

    // ─── Permissions ─────────────────────────────────────────────

    private fun requestPermsAndScan() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
        val allGranted = perms.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startScan()
        else ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else tvScanStatus.text = getString(R.string.msg_perms_denied)
        }
    }

    // ─── BLE Scan ────────────────────────────────────────────────

    private fun startScan() {
        foundDevices.clear()
        deviceNames.clear()
        deviceAdapter.notifyDataSetChanged()
        scanProgress.visibility = View.VISIBLE
        tvScanStatus.text = getString(R.string.msg_scanning)
        btnScan.isEnabled     = false
        btnStopScan.isEnabled = true
        try {
            WitBluetoothManager.initInstance(this)
            val bt = WitBluetoothManager.getInstance()
            bt.registerObserver(this)
            bt.startDiscovery()
        } catch (e: BluetoothBLEException) {
            tvScanStatus.text = getString(R.string.msg_scan_error, sdkMsg(e.message))
            scanProgress.visibility = View.GONE
            btnScan.isEnabled = true
        } catch (e: Exception) {
            tvScanStatus.text = getString(R.string.msg_error, sdkMsg(e.message))
            scanProgress.visibility = View.GONE
            btnScan.isEnabled = true
        }
    }

    private fun stopScan() {
        scanProgress.visibility = View.GONE
        btnScan.isEnabled     = true
        btnStopScan.isEnabled = false
        try {
            val bt = WitBluetoothManager.getInstance()
            bt.removeObserver(this)
            bt.stopDiscovery()
        } catch (e: Exception) { }
        tvScanStatus.text = getString(R.string.msg_devices_found_stop, foundDevices.size)
    }

    private fun sdkMsg(msg: String?): String {
        if (msg == null) return getString(R.string.sdk_unknown_error)
        return when {
            msg.contains("蓝牙") && msg.contains("打开") -> getString(R.string.sdk_enable_bt)
            msg.contains("定位") || msg.contains("位置") -> getString(R.string.sdk_enable_location)
            msg.contains("权限")                         -> getString(R.string.sdk_missing_perm)
            msg.any { it.code in 0x4E00..0x9FFF }        -> getString(R.string.sdk_generic_error)
            else -> msg
        }
    }

    override fun onFoundBle(ble: BluetoothBLE) {
        try {
            val device = Bwt901ble(ble)
            val name   = device.deviceName?.takeIf { it.isNotBlank() }
                         ?: ble.toString().takeLast(17).ifBlank { "BLE-${foundDevices.size + 1}" }
            runOnUiThread {
                if (foundDevices.none { (it.deviceName ?: "") == name }) {
                    foundDevices.add(device)
                    deviceNames.add(name)
                    deviceAdapter.notifyDataSetChanged()
                    tvScanStatus.text = getString(R.string.msg_device_count, foundDevices.size)
                }
            }
        } catch (e: Exception) { }
    }

    override fun onFoundSPP(spp: BluetoothSPP) { }

    // ─── Connection ──────────────────────────────────────────────

    private fun connectTo(device: Bwt901ble) {
        stopScan()
        device.registerRecordObserver(this)
        try {
            device.open()
        } catch (e: OpenDeviceException) {
            Toast.makeText(this, getString(R.string.msg_connect_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        connectedDevice = device
        referenceAngle  = 0f
        lastRawAngle    = 0f
        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 85)
        runOnUiThread {
            tvDeviceName.text = device.deviceName ?: "WT901"
            tvBattery.text    = "🔋 --%"
            tvAngle.text      = getString(R.string.msg_ble_init)
            viewFlipper.displayedChild = 1
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (connectedDevice != null) startPolling(device)
        }, 1500)
    }

    private fun disconnect() {
        stopPolling()
        stopBeep()
        connectedDevice?.let {
            it.removeRecordObserver(this)
            it.close()
        }
        connectedDevice = null
        toneGen?.release()
        toneGen = null
        runOnUiThread {
            viewFlipper.displayedChild  = 0
            btnScan.isEnabled           = true
            btnStopScan.isEnabled       = false
            scanProgress.visibility     = View.GONE
            tvScanStatus.text           = getString(R.string.msg_disconnected)
            foundDevices.clear()
            deviceNames.clear()
            deviceAdapter.notifyDataSetChanged()
        }
    }

    // ─── Sensor data ─────────────────────────────────────────────

    override fun onRecord(device: Bwt901ble) { }

    private fun startPolling(device: Bwt901ble) {
        polling = true
        pollThread = Thread {
            var ticksSinceData = 0
            while (polling) {
                try { Thread.sleep(100) } catch (e: InterruptedException) { break }

                val aX  = device.getDeviceData(WitSensorKey.AccX)
                val aY  = device.getDeviceData(WitSensorKey.AccY)
                val aZ  = device.getDeviceData(WitSensorKey.AccZ)
                val bat = device.getDeviceData(WitSensorKey.ElectricQuantityPercentage)

                fun String?.toSafeFloat() = this?.replace(",", ".")?.trim()?.toFloatOrNull()
                val accXf = aX.toSafeFloat()
                val accYf = aY.toSafeFloat()
                val accZf = aZ.toSafeFloat()

                val pitch: Float?
                val roll:  Float?
                val raw:   Float?
                if (accXf != null && accYf != null && accZf != null) {
                    pitch = Math.toDegrees(atan2(accXf.toDouble(), sqrt((accYf * accYf + accZf * accZf).toDouble()))).toFloat()
                    roll  = Math.toDegrees(atan2(accYf.toDouble(), accZf.toDouble())).toFloat()
                    raw   = when (axisIndex) {
                        0    -> pitch
                        1    -> -pitch
                        else -> roll
                    }
                } else {
                    pitch = null; roll = null; raw = null
                }

                if (raw != null) ticksSinceData = 0 else ticksSinceData++

                runOnUiThread {
                    if (!polling) return@runOnUiThread
                    if (raw != null) {
                        tvAngle.text = "${String.format("%.1f", raw)}°"
                        val dev = raw - referenceAngle
                        lastRawAngle = raw
                        levelView.deviation = dev
                        tvBattery.text = "🔋 ${bat ?: "--"}%"
                        updateBeep(dev)
                    } else {
                        val waited = ticksSinceData * 100 / 1000
                        tvAngle.text = if (waited > 5)
                            getString(R.string.msg_no_data, waited)
                        else
                            getString(R.string.msg_ble_init_short)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    // ─── Beep — progressive proximity alert ──────────────────────

    private fun updateBeep(deviation: Float) {
        if (!cbBeep.isChecked) { stopBeep(); return }
        val abs = abs(deviation)

        if (abs > BEEP_START_DEG) { stopBeep(); return }

        val targetInterval: Long = when {
            abs <= BEEP_CONT_DEG -> 0L  // continuous tone
            else -> {
                // Linear: 900 ms at 15°, 80 ms at 0.4°
                val ratio = (abs - BEEP_CONT_DEG) / (BEEP_START_DEG - BEEP_CONT_DEG)
                (80 + ratio * (BEEP_MAX_MS - 80)).toLong()
            }
        }

        if (targetInterval == beepInterval) return  // no change

        beepHandler.removeCallbacksAndMessages(null)
        toneGen?.stopTone()
        beepInterval = targetInterval

        if (targetInterval == 0L) {
            toneGen?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, Int.MAX_VALUE)
        } else {
            scheduleBeep()
        }
    }

    private fun scheduleBeep() {
        val r = object : Runnable {
            override fun run() {
                if (beepInterval <= 0L) return
                toneGen?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 80)
                beepHandler.postDelayed(this, beepInterval)
            }
        }
        beepHandler.post(r)
    }

    private fun stopBeep() {
        if (beepInterval == -1L) return
        beepInterval = -1L
        beepHandler.removeCallbacksAndMessages(null)
        toneGen?.stopTone()
    }

    // ─── Cleanup ─────────────────────────────────────────────────

    private fun cleanup() {
        stopPolling()
        stopBeep()
        connectedDevice?.let {
            it.removeRecordObserver(this)
            it.close()
        }
        toneGen?.release()
        try {
            val bt = WitBluetoothManager.getInstance()
            bt.removeObserver(this)
            bt.stopDiscovery()
        } catch (e: Exception) { }
    }
}
