package com.truckcontroller.pro.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R

/** Mobile network test: SIM, operator, 4G/5G, live signal strength and quality. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CellularTestActivity : HardwareTestActivity() {

    private lateinit var tm: TelephonyManager
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var bars: SignalBarsView
    private val rows = HashMap<String, TextView>()
    private var displayInfo: TelephonyDisplayInfo? = null
    private var displayCallback: Any? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun requiredPermissions() = listOf(Manifest.permission.READ_PHONE_STATE)
    override val permissionReason = "Phone permission is needed to read the network type (4G/5G) and SIM status. " +
        "No calls, numbers or messages are read."

    private val ticker = object : Runnable {
        override fun run() {
            show()
            handler.postDelayed(this, 2000)
        }
    }

    override fun buildTest() {
        tm = getSystemService(TelephonyManager::class.java)
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
        })
        page.addCard(titledCard("SIGNAL", R.drawable.ic_signal, accent).apply {
            listOf("Strength", "Level", "Quality (RSRQ)", "Signal/noise (SNR)", "Network type").forEach { rows[it] = infoRow(it) }
        })
        page.addCard(titledCard("SIM & NETWORK", R.drawable.ic_smartphone, accent).apply {
            listOf("SIM", "SIM operator", "Network", "Country", "Roaming", "Mobile data", "SIM slots", "eSIM")
                .forEach { rows[it] = infoRow(it) }
        })
    }

    override fun startListening() {
        handler.post(ticker)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { displayInfo = info; show() }
            }
            runCatching { tm.registerTelephonyCallback(mainExecutor, cb); displayCallback = cb }
        }
    }

    override fun stopListening() {
        handler.removeCallbacks(ticker)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (displayCallback as? TelephonyCallback)?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
            displayCallback = null
        }
    }

    private fun show() {
        val simState = tm.simState
        val simReady = simState == TelephonyManager.SIM_STATE_READY
        val net = networkType()
        val strength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.signalStrength else null
        val cell: CellSignalStrength? = strength?.cellSignalStrengths?.maxByOrNull { it.level }
        val level = strength?.level ?: 0

        status.text = when {
            !simReady -> simText(simState)
            tm.networkOperatorName.isNullOrBlank() -> "No service"
            else -> "${tm.networkOperatorName} · $net"
        }
        status.setTextColor(if (simReady) levelColor(level) else hex("#FBBF24"))
        statusSub.text = if (simReady) when (level) {
            4 -> "Excellent signal"; 3 -> "Good signal"; 2 -> "Fair signal"; 1 -> "Poor signal"; else -> "No signal"
        } else "Insert a SIM to test the mobile network."
        bars.level = level

        rows["Strength"]?.apply {
            text = cell?.dbm?.takeIf { it != Int.MAX_VALUE }?.let { "$it dBm" } ?: "—"
            setTextColor(levelColor(level))
        }
        rows["Level"]?.text = "$level / 4"
        rows["Quality (RSRQ)"]?.text = when (cell) {
            is CellSignalStrengthLte -> cell.rsrq.takeIf { it != Int.MAX_VALUE }?.let { "$it dB" }
            is CellSignalStrengthNr -> cell.ssRsrq.takeIf { it != Int.MAX_VALUE }?.let { "$it dB" }
            else -> null
        } ?: "—"
        rows["Signal/noise (SNR)"]?.text = when (cell) {
            is CellSignalStrengthLte -> cell.rssnr.takeIf { it != Int.MAX_VALUE }?.let { "$it dB" }
            is CellSignalStrengthNr -> cell.ssSinr.takeIf { it != Int.MAX_VALUE }?.let { "$it dB" }
            else -> null
        } ?: "—"
        rows["Network type"]?.text = net

        rows["SIM"]?.text = simText(simState)
        rows["SIM operator"]?.text = tm.simOperatorName.ifBlank { "—" }
        rows["Network"]?.text = tm.networkOperatorName.ifBlank { "—" }
        rows["Country"]?.text = tm.networkCountryIso.uppercase().ifBlank { "—" }
        rows["Roaming"]?.setYesNo(tm.isNetworkRoaming)
        rows["Mobile data"]?.setYesNo(runCatching { tm.isDataEnabled }.getOrNull())
        rows["SIM slots"]?.text = "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount else tm.phoneCount}"
        rows["eSIM"]?.setYesNo(getSystemService(EuiccManager::class.java)?.isEnabled)
    }

    private fun networkType(): String {
        val info = displayInfo
        if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (info.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> return "5G (NSA)"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return "5G+"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> return "4G+ (LTE-A Pro)"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> return "4G+ (LTE-CA)"
            }
        }
        val type = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G (SA)"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi calling"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
            else -> "No data"
        }
    }

    private fun simText(state: Int) = when (state) {
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Restricted"
        else -> "Unknown"
    }
}
