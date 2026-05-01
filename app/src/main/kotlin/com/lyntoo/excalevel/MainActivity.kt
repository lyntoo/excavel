package com.lyntoo.excalevel

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
        private const val PERM_REQUEST   = 101
        private const val REQUEST_BT_ON  = 102
        private const val PREFS_NAME     = "excalevel"
        private const val PREF_ORIENT    = "orientation"   // "landscape" | "portrait"
        private const val PREF_AXIS      = "axis_index"
    }

    // BLE
    private val foundDevices   = mutableListOf<Bwt901ble>()
    private val deviceNames    = mutableListOf<String>()
    private var connectedDevice: Bwt901ble? = null
    private lateinit var deviceAdapter: ArrayAdapter<String>

    // Mesure — 0=Bras X+, 1=Bras X−, 2=Roulis Y/Z
    private val axisLabels = arrayOf("Bras (X+)", "Bras inv (X−)", "Roulis (Y/Z)")
    private var axisIndex      = 0
    private var referenceAngle = 0f
    private var lastRawAngle   = 0f

    // Beep
    private var toneGen: ToneGenerator? = null
    private val beepHandler = Handler(Looper.getMainLooper())
    private var beepActive  = false

    // Polling thread (même approche que l'app originale WitMotion)
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    // Prefs
    private lateinit var prefs: SharedPreferences

    // Vues — scan
    private lateinit var viewFlipper:  ViewFlipper
    private lateinit var btnScan:      Button
    private lateinit var btnStopScan:  Button
    private lateinit var scanProgress: ProgressBar
    private lateinit var tvScanStatus: TextView
    private lateinit var lvDevices:    ListView

    // Vues — niveau
    private lateinit var tvDeviceName:  TextView
    private lateinit var tvBattery:     TextView
    private lateinit var tvAngle:       TextView
    private lateinit var levelView:     LevelView
    private lateinit var spinnerAxis:   Spinner
    private lateinit var btnCalibrate:  Button
    private lateinit var cbBeep:        CheckBox
    private lateinit var btnDisconnect: Button
    private lateinit var btnRotate:     Button   // toggle portrait/landscape

    // ─── Lifecycle ───────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Appliquer l'orientation sauvegardée AVANT setContentView
        applyOrientation(prefs.getString(PREF_ORIENT, "landscape") ?: "landscape")

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        setupAxisSpinner()
        setupListeners()
        updateRotateButton()

        // Laisser le SDK gérer l'init initiale (comme l'exemple original)
        try {
            WitBluetoothManager.requestPermissions(this)
            WitBluetoothManager.initInstance(this)
        } catch (e: Exception) {
            // Ignoré ici — réinitialisé dans startScan() après permissions confirmées
        }
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
        btnRotate.text = if (orient == "landscape") "📱 Portrait" else "🖥 Landscape"
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
        axisIndex = prefs.getInt(PREF_AXIS, 1)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, axisLabels)
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
        btnScan.setOnClickListener      { checkPrerequisitesAndScan() }
        btnStopScan.setOnClickListener  { stopScan() }
        btnRotate.setOnClickListener    { toggleOrientation() }

        lvDevices.setOnItemClickListener { _, _, pos, _ ->
            if (pos < foundDevices.size) connectTo(foundDevices[pos])
        }

        btnCalibrate.setOnClickListener {
            referenceAngle = lastRawAngle
            Toast.makeText(this, "Calibré — référence: ${String.format("%.1f", referenceAngle)}°", Toast.LENGTH_SHORT).show()
        }

        cbBeep.setOnCheckedChangeListener { _, checked ->
            if (!checked) stopBeep()
        }

        btnDisconnect.setOnClickListener { disconnect() }
    }

    // ─── Vérifications pré-scan ──────────────────────────────────

    private fun checkPrerequisitesAndScan() {
        // 1. Bluetooth activé ?
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            tvScanStatus.text = "Bluetooth désactivé — activation en cours..."
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_BT_ON)
            return
        }

        // 2. Services de localisation activés ? (requis pour BLE sur Android)
        val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locManager)) {
            tvScanStatus.text = "Services de localisation requis pour le scan BLE — veuillez les activer"
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // 3. Permissions
        requestPermsAndScan()
    }

    @Deprecated("Needed for BT enable result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BT_ON) {
            if (resultCode == RESULT_OK) checkPrerequisitesAndScan()
            else tvScanStatus.text = "Bluetooth refusé — impossible de scanner"
        }
    }

    // ─── Permissions ─────────────────────────────────────────────

    private fun requestPermsAndScan() {
        // Location toujours requise pour BLE (toutes versions Android)
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
            else tvScanStatus.text = "Permissions refusées — Bluetooth + Localisation requis pour le scan BLE"
        }
    }

    // ─── Scan BLE ────────────────────────────────────────────────

    private fun startScan() {
        foundDevices.clear()
        deviceNames.clear()
        deviceAdapter.notifyDataSetChanged()
        scanProgress.visibility = View.VISIBLE
        tvScanStatus.text = "Scan en cours... touchez un appareil pour connecter"
        btnScan.isEnabled     = false
        btnStopScan.isEnabled = true

        try {
            // Réinitialiser avec les permissions maintenant accordées
            WitBluetoothManager.initInstance(this)
            val bt = WitBluetoothManager.getInstance()
            bt.registerObserver(this)
            bt.startDiscovery()
        } catch (e: BluetoothBLEException) {
            tvScanStatus.text = "Erreur scan: ${sdkMsg(e.message)}"
            scanProgress.visibility = View.GONE
            btnScan.isEnabled = true
        } catch (e: Exception) {
            tvScanStatus.text = "Erreur: ${sdkMsg(e.message)}"
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
        tvScanStatus.text = "${foundDevices.size} appareil(s) trouvé(s). Touchez pour connecter."
    }

    // Traduit les messages chinois du SDK WitMotion en français
    private fun sdkMsg(msg: String?): String {
        if (msg == null) return "Erreur inconnue"
        return when {
            msg.contains("蓝牙") && msg.contains("打开") -> "Veuillez activer le Bluetooth"
            msg.contains("定位") || msg.contains("位置") -> "Veuillez activer les services de localisation"
            msg.contains("权限")                         -> "Permission manquante"
            msg.any { it.code in 0x4E00..0x9FFF }        -> "Erreur SDK: vérifiez Bluetooth et localisation"
            else -> msg
        }
    }

    override fun onFoundBle(ble: BluetoothBLE) {
        try {
            val device = Bwt901ble(ble)
            val name   = device.deviceName?.takeIf { it.isNotBlank() }
                         ?: ble.toString().takeLast(17).ifBlank { "BLE-${foundDevices.size + 1}" }
            runOnUiThread {
                // Déduplication par nom
                if (foundDevices.none { (it.deviceName ?: "") == name }) {
                    foundDevices.add(device)
                    deviceNames.add(name)
                    deviceAdapter.notifyDataSetChanged()
                    tvScanStatus.text = "${foundDevices.size} appareil(s) trouvé(s)"
                }
            }
        } catch (e: Exception) {
            // Appareil BLE non compatible WitSDK — ignorer silencieusement
        }
    }

    override fun onFoundSPP(spp: BluetoothSPP) { }

    // ─── Connexion ───────────────────────────────────────────────

    private fun connectTo(device: Bwt901ble) {
        stopScan()
        device.registerRecordObserver(this)
        try {
            device.open()
        } catch (e: OpenDeviceException) {
            Toast.makeText(this, "Connexion échouée: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        connectedDevice = device
        referenceAngle  = 0f
        lastRawAngle    = 0f
        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 85)

        runOnUiThread {
            tvDeviceName.text = device.deviceName ?: "WT901"
            tvBattery.text    = "🔋 --%"
            tvAngle.text      = "Initialisation BLE GATT..."
            viewFlipper.displayedChild = 1
        }

        // Délai 1.5s pour laisser les notifications BLE GATT s'établir avant de lire
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
        toneGen    = null
        beepActive = false
        runOnUiThread {
            viewFlipper.displayedChild = 0
            btnScan.isEnabled     = true
            btnStopScan.isEnabled = false
            scanProgress.visibility = View.GONE
            tvScanStatus.text = "Déconnecté."
            foundDevices.clear()
            deviceNames.clear()
            deviceAdapter.notifyDataSetChanged()
        }
    }

    // ─── Données capteur ─────────────────────────────────────────

    // onRecord = callback optionnel du SDK (pas toujours appelé selon firmware)
    // Les données réelles sont lues via le polling thread ci-dessous
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

                // Conversion robuste : remplace la virgule locale par un point
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
                        0    -> pitch          // Bras X+
                        1    -> -pitch         // Bras X− (inversé)
                        else -> roll           // Roulis Y/Z
                    }
                } else {
                    pitch = null; roll = null; raw = null
                }

                if (raw != null) ticksSinceData = 0 else ticksSinceData++

                runOnUiThread {
                    if (!polling) return@runOnUiThread

                    if (raw != null && pitch != null && roll != null) {
                        tvAngle.text = "${String.format("%.1f", raw)}°"
                        val dev = raw - referenceAngle
                        lastRawAngle = raw
                        levelView.deviation = dev
                        tvBattery.text = "🔋 ${bat ?: "--"}%"
                        updateBeep(dev)
                    } else {
                        val waited = ticksSinceData * 100 / 1000
                        tvAngle.text = if (waited > 5)
                            "⚠️ Pas de données ($waited s)"
                        else
                            "Init BLE..."
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

    // ─── Beep ────────────────────────────────────────────────────

    private fun updateBeep(deviation: Float) {
        if (!cbBeep.isChecked) return
        val inZone = abs(deviation) <= levelView.toleranceDeg
        if (inZone && !beepActive)  startBeep()
        else if (!inZone && beepActive) stopBeep()
    }

    private fun startBeep() {
        beepActive = true
        val r = object : Runnable {
            override fun run() {
                if (!beepActive) return
                toneGen?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 180)
                beepHandler.postDelayed(this, 380)
            }
        }
        beepHandler.post(r)
    }

    private fun stopBeep() {
        beepActive = false
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
