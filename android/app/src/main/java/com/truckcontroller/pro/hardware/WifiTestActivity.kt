package com.truckcontroller.pro.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R
import java.net.Inet4Address

/** Wi-Fi test: current connection, chip capabilities and a scan of nearby networks. */
@Suppress("DEPRECATION")
class WifiTestActivity : HardwareTestActivity() {

    private lateinit var wifi: WifiManager
    private lateinit var connectivity: ConnectivityManager
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var bars: SignalBarsView
    private val rows = HashMap<String, TextView>()
    private lateinit var nearby: LinearLayout
    private lateinit var scanInfo: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun requiredPermissions() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    override val permissionReason =
        "Android only reveals Wi-Fi network names and nearby networks to apps with location permission. Nothing is stored or sent."

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showScan()
    }

    private val ticker = object : Runnable {
        override fun run() {
            showConnection()
            handler.postDelayed(this, 2000)
        }
    }

    override fun buildTest() {
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)

        page.addCard(card().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    status = statusText()
                    statusSub = smallText()
                    addView(status)
                    addView(statusSub)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                bars = SignalBarsView(context)
                addView(bars, LinearLayout.LayoutParams(dp(40), dp(32)))
            })
            addView(buttonRow(actionButton("Wi-Fi settings", filled = false) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        })

        page.addCard(titledCard("CONNECTION", R.drawable.ic_wifi, accent).apply {
            listOf("Signal", "Link speed", "Upload / download", "Band", "Channel", "Wi-Fi standard", "Security",
                "Router (BSSID)", "IP address", "Gateway", "DNS").forEach { rows[it] = infoRow(it) }
        })

        page.addCard(titledCard("CHIP CAPABILITIES", R.drawable.ic_cpu, accent).apply {
            infoRow("5 GHz band").setYesNo(wifi.is5GHzBandSupported)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                infoRow("6 GHz band (Wi-Fi 6E)").setYesNo(wifi.is6GHzBandSupported)
                infoRow("Wi-Fi 5 (802.11ac)").setYesNo(wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC))
                infoRow("Wi-Fi 6 (802.11ax)").setYesNo(wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                infoRow("Wi-Fi 7 (802.11be)").setYesNo(wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                infoRow("WPA3 security").setYesNo(wifi.isWpa3SaeSupported)
                infoRow("Enhanced Open (OWE)").setYesNo(wifi.isEnhancedOpenSupported)
            }
            infoRow("Wi-Fi Direct").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
            infoRow("Wi-Fi Aware").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
            infoRow("Indoor positioning (RTT)").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
            infoRow("Hotspot while on Wi-Fi").setYesNo(wifi.isStaApConcurrencySupported.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R })
        })

        nearby = titledCard("NEARBY NETWORKS", R.drawable.ic_radar, accent)
        scanInfo = smallText("Tap Scan to search for networks.")
        nearby.addView(scanInfo)
        nearby.addView(buttonRow(actionButton("Scan") { startScan() }),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
        page.addCard(nearby)
    }

    override fun startListening() {
        ContextCompat.registerReceiver(this, scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED)
        handler.post(ticker)
        showScan()
    }

    override fun stopListening() {
        unregisterReceiver(scanReceiver)
        handler.removeCallbacks(ticker)
    }

    private fun startScan() {
        if (!wifi.isWifiEnabled) {
            scanInfo.text = "Turn Wi-Fi on to scan."
            return
        }
        scanInfo.text = if (wifi.startScan()) "Scanning…"
        else "Android limits scans to 4 every 2 minutes; showing the latest results."
        showScan()
    }

    @SuppressLint("MissingPermission")
    private fun showScan() {
        val results = runCatching { wifi.scanResults }.getOrNull().orEmpty()
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }.map { (_, g) -> g.maxBy { it.level } }
            .sortedByDescending { it.level }
        while (nearby.childCount > 3) nearby.removeViewAt(3)
        if (results.isEmpty()) return
        if (scanInfo.text == "Scanning…" || scanInfo.text.startsWith("Tap")) scanInfo.text = "${results.size} networks found"
        results.take(40).forEach { r -> nearby.addView(networkRow(r)) }
    }

    private fun networkRow(r: ScanResult) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(SignalBarsView(context).apply { level = rssiLevel(r.level) }, LinearLayout.LayoutParams(dp(22), dp(16)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = r.SSID
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(smallText(listOfNotNull(
                band(r.frequency), "ch ${channel(r.frequency)}", security(r.capabilities),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) standard(r.wifiStandard) else null,
            ).joinToString(" · ")))
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        addView(TextView(context).apply {
            text = "${r.level} dBm"
            textSize = 13f
            setTextColor(levelColor(rssiLevel(r.level)))
        })
    }

    @SuppressLint("MissingPermission")
    private fun showConnection() {
        if (!wifi.isWifiEnabled) {
            status.text = "Wi-Fi is off"
            status.setTextColor(hex("#F87171"))
            statusSub.text = "Turn it on in Wi-Fi settings to test."
            bars.level = 0
            return
        }
        val info = wifi.connectionInfo
        if (info == null || info.networkId == -1 && info.rssi <= -127) {
            status.text = "Not connected"
            status.setTextColor(hex("#FBBF24"))
            statusSub.text = "Wi-Fi is on. Connect to a network to test the link."
            bars.level = 0
            rows.values.forEach { it.text = "—" }
            return
        }
        val ssid = info.ssid.removeSurrounding("\"").takeUnless { it == "<unknown ssid>" } ?: "Connected network"
        val level = rssiLevel(info.rssi)
        status.text = ssid
        status.setTextColor(Color.WHITE)
        statusSub.text = when (level) { 4 -> "Excellent signal"; 3 -> "Good signal"; 2 -> "Fair signal"; else -> "Weak signal" }
        bars.level = level
        rows["Signal"]?.apply { text = "${info.rssi} dBm"; setTextColor(levelColor(level)) }
        rows["Link speed"]?.text = "${info.linkSpeed} Mbps"
        rows["Upload / download"]?.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${info.txLinkSpeedMbps} / ${info.rxLinkSpeedMbps} Mbps" else "—"
        rows["Band"]?.text = band(info.frequency) + " (${info.frequency} MHz)"
        rows["Channel"]?.text = "${channel(info.frequency)}"
        rows["Wi-Fi standard"]?.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) standard(info.wifiStandard) ?: "—" else "—"
        rows["Security"]?.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) securityType(info.currentSecurityType) else "—"
        rows["Router (BSSID)"]?.text = info.bssid?.takeUnless { it == "02:00:00:00:00:00" } ?: "Hidden"
        val link = connectivity.allNetworks.firstNotNullOfOrNull { n ->
            val caps = connectivity.getNetworkCapabilities(n)
            if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) connectivity.getLinkProperties(n) else null
        }
        rows["IP address"]?.text = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: "—"
        rows["Gateway"]?.text = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: "—"
        rows["DNS"]?.text = link?.dnsServers?.take(2)?.joinToString(", ") { it.hostAddress ?: "" }?.ifBlank { "—" } ?: "—"
    }

    private fun band(mhz: Int) = when {
        mhz in 2400..2500 -> "2.4 GHz"; mhz in 4900..5900 -> "5 GHz"; mhz in 5925..7125 -> "6 GHz"; mhz > 50000 -> "60 GHz"
        else -> "$mhz MHz"
    }

    private fun channel(mhz: Int) = when {
        mhz == 2484 -> 14
        mhz in 2400..2500 -> (mhz - 2407) / 5
        mhz in 4900..5900 -> (mhz - 5000) / 5
        mhz in 5925..7125 -> (mhz - 5950) / 5
        else -> 0
    }

    private fun standard(s: Int) = when (s) {
        1 -> "Wi-Fi 1–3 (a/b/g)"; 4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "WiGig (ad)"; 7 -> "Wi-Fi 6 (ax)"
        8 -> "Wi-Fi 7 (be)"; else -> null
    }

    private fun security(caps: String) = when {
        "SAE" in caps -> "WPA3"; "EAP" in caps -> "Enterprise"; "PSK" in caps -> "WPA2"; "WEP" in caps -> "WEP"
        "OWE" in caps -> "Enhanced Open"; else -> "Open"
    }

    private fun securityType(t: Int) = when (t) {
        0 -> "Open"; 1 -> "WEP"; 2 -> "WPA2 (PSK)"; 3 -> "Enterprise"; 4 -> "WPA3 (SAE)"; 5 -> "WPA3 Enterprise 192-bit"
        6 -> "Enhanced Open (OWE)"; 9 -> "WPA3 Enterprise"; else -> "—"
    }
}
