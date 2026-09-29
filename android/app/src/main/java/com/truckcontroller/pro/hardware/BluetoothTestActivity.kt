package com.truckcontroller.pro.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R

/** Bluetooth test: adapter status and capabilities, paired devices and a scan of nearby devices. */
@SuppressLint("MissingPermission")
class BluetoothTestActivity : HardwareTestActivity() {

    private class Found(val address: String, var name: String?, var rssi: Int, var type: String, var kind: String?)

    private var adapter: BluetoothAdapter? = null
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var enableButton: TextView
    private lateinit var paired: LinearLayout
    private lateinit var nearby: LinearLayout
    private lateinit var scanInfo: TextView
    private lateinit var scanButton: TextView
    private val found = LinkedHashMap<String, Found>()
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    override fun requiredPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override val permissionReason =
        "Nearby-devices and location permissions are needed to scan for Bluetooth devices. Nothing is stored or sent."

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val entry = found.getOrPut(d.address) { Found(d.address, null, result.rssi, "BLE", null) }
            entry.rssi = result.rssi
            entry.name = entry.name ?: result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull()
            showNearby()
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    d ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val entry = found.getOrPut(d.address) { Found(d.address, null, rssi, "Classic", null) }
                    entry.type = if (entry.type == "BLE") "Dual" else entry.type
                    if (rssi != Short.MIN_VALUE.toInt()) entry.rssi = rssi
                    entry.name = runCatching { d.name }.getOrNull() ?: entry.name
                    entry.kind = runCatching { deviceKind(d.bluetoothClass) }.getOrNull()
                    showNearby()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> showAdapter()
            }
        }
    }

    override fun buildTest() {
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        page.addCard(card().apply {
            status = statusText()
            statusSub = smallText()
            addView(status)
            addView(statusSub)
            enableButton = actionButton("Turn on Bluetooth") {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            addView(buttonRow(enableButton), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        })

        val a = adapter ?: return
        page.addCard(titledCard("CAPABILITIES", R.drawable.ic_cpu, accent).apply {
            infoRow("Bluetooth Low Energy").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            val twoM = a.isLe2MPhySupported
            infoRow("Bluetooth version", when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    a.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED -> "5.2 or newer"
                twoM || a.isLeCodedPhySupported || a.isLeExtendedAdvertisingSupported -> "5.0 or newer"
                else -> "4.x"
            })
            infoRow("LE 2M PHY (2× speed)").setYesNo(twoM)
            infoRow("LE Coded PHY (4× range)").setYesNo(a.isLeCodedPhySupported)
            infoRow("Extended advertising").setYesNo(a.isLeExtendedAdvertisingSupported)
            infoRow("Periodic advertising").setYesNo(a.isLePeriodicAdvertisingSupported)
            infoRow("Max advertising data", "${a.leMaximumAdvertisingDataLength} bytes")
            infoRow("Multiple advertisers").setYesNo(a.isMultipleAdvertisementSupported)
            infoRow("Hardware scan filters").setYesNo(a.isOffloadedFilteringSupported)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                infoRow("LE Audio").setYesNo(a.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED)
                infoRow("LE Audio broadcast (Auracast)")
                    .setYesNo(a.isLeAudioBroadcastSourceSupported == BluetoothStatusCodes.FEATURE_SUPPORTED)
            }
        })

        paired = titledCard("PAIRED DEVICES", R.drawable.ic_link, accent)
        page.addCard(paired)

        nearby = titledCard("NEARBY DEVICES", R.drawable.ic_radar, accent)
        scanInfo = smallText("Tap Scan to search for 15 seconds.")
        nearby.addView(scanInfo)
        scanButton = actionButton("Scan") { if (scanning) stopScan() else startScan() }
        nearby.addView(buttonRow(scanButton), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
        page.addCard(nearby)
    }

    override fun startListening() {
        ContextCompat.registerReceiver(this, classicReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        showAdapter()
    }

    override fun stopListening() {
        stopScan()
        unregisterReceiver(classicReceiver)
    }

    private fun showAdapter() {
        val a = adapter
        if (a == null) {
            status.text = "No Bluetooth adapter"
            status.setTextColor(hex("#F87171"))
            enableButton.visibility = android.view.View.GONE
            return
        }
        val on = a.isEnabled
        status.text = if (on) "Bluetooth is on" else "Bluetooth is off"
        status.setTextColor(hex(if (on) "#34D399" else "#FBBF24"))
        val audio = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).any {
            runCatching { a.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        }
        statusSub.text = listOfNotNull(
            runCatching { a.name }.getOrNull()?.let { "Name: $it" },
            if (on && audio) "Audio device connected" else null,
        ).joinToString(" · ")
        enableButton.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        if (::paired.isInitialized) showPaired()
    }

    private fun showPaired() {
        while (paired.childCount > 1) paired.removeViewAt(1)
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isEmpty()) {
            paired.addView(smallText(if (adapter?.isEnabled == true) "No paired devices." else "Turn Bluetooth on to see paired devices."))
            return
        }
        bonded.sortedBy { it.name ?: "" }.forEach { d ->
            paired.addView(deviceRow(d.name ?: d.address, listOfNotNull(typeName(d.type), deviceKind(d.bluetoothClass)).joinToString(" · "), null))
        }
    }

    private fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) {
            scanInfo.text = "Turn Bluetooth on to scan."
            return
        }
        found.clear()
        scanning = true
        scanButton.text = "Stop"
        scanInfo.text = "Scanning…"
        runCatching {
            a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        }
        runCatching { a.startDiscovery() }
        handler.postDelayed({ stopScan() }, 15_000)
        showNearby()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.cancelDiscovery() }
        scanButton.text = "Scan again"
        scanInfo.text = "${found.size} devices found"
    }

    private fun showNearby() {
        if (scanning) scanInfo.text = "Scanning… ${found.size} found"
        while (nearby.childCount > 3) nearby.removeViewAt(3)
        found.values.sortedByDescending { it.rssi }.take(50).forEach { f ->
            nearby.addView(deviceRow(f.name ?: "Unnamed device", listOfNotNull(f.type, f.kind, f.address).joinToString(" · "), f.rssi))
        }
    }

    private fun deviceRow(name: String, detail: String, rssi: Int?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        if (rssi != null) addView(SignalBarsView(context).apply { level = rssiLevel(rssi) }, LinearLayout.LayoutParams(dp(22), dp(16)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = name
                textSize = 14f
                maxLines = 1
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(smallText(detail).apply { maxLines = 1 })
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (rssi != null) marginStart = dp(12) })
        if (rssi != null) addView(TextView(context).apply {
            text = "$rssi dBm"
            textSize = 13f
            setTextColor(levelColor(rssiLevel(rssi)))
        })
    }

    private fun typeName(type: Int) = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
        else -> null
    }

    private fun deviceKind(c: BluetoothClass?) = when (c?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.PERIPHERAL -> "Input device"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.IMAGING -> "Printer / camera"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.NETWORKING -> "Network"
        else -> null
    }
}
