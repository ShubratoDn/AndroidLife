package com.truckcontroller.pro

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/** Device Information: hardware, system, CPU, memory, display, cameras and features. */
class DeviceInfoActivity : ToolActivity() {

    private data class Section(val title: String, val icon: Int, val rows: List<Pair<String, String>>)

    private val accent = Color.parseColor("#60A5FA")
    private val handler = Handler(Looper.getMainLooper())
    private var ramAvailable: TextView? = null
    private var ramUsed: TextView? = null
    private var uptime: TextView? = null
    private lateinit var sections: List<Section>

    private val ticker = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sections = collect()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        list.addView(heroCard())
        sections.forEach { section ->
            val card = titledCard(section.title, section.icon, accent)
            section.rows.forEach { (label, value) ->
                val view = card.infoRow(label, value)
                when (label) {
                    "Available RAM" -> ramAvailable = view
                    "RAM in use" -> ramUsed = view
                    "Uptime" -> uptime = view
                }
            }
            list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        list.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            text = "Long-press any row to copy it"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.slate_600))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        setContentView(toolPage("Device Info", R.drawable.ic_smartphone, accent, ScrollView(this).apply { addView(list) },
            R.drawable.ic_share to { shareText("Share device info", asText()) }))
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    private fun heroCard() = card().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        addView(TextView(context).apply {
            text = "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
            text = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · ${Build.DEVICE}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(accent)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
    }

    private fun updateLive() {
        val (total, available) = memory()
        ramAvailable?.text = formatBytes(available)
        ramUsed?.text = "${((total - available) * 100 / total.coerceAtLeast(1))}% · ${formatBytes(total - available)}"
        uptime?.text = duration(SystemClock.elapsedRealtime())
    }

    private fun memory(): Pair<Long, Long> {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem to info.availMem
    }

    private fun collect(): List<Section> {
        val (ramTotal, ramAvail) = memory()
        val internal = StatFs(Environment.getDataDirectory().path)
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(it)
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val inches = sqrt((metrics.widthPixels / metrics.xdpi).let { it * it } + (metrics.heightPixels / metrics.ydpi).let { it * it })
        val rates = display.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted()

        return listOf(
            Section("DEVICE", R.drawable.ic_smartphone, listOf(
                "Manufacturer" to Build.MANUFACTURER.replaceFirstChar(Char::uppercase),
                "Brand" to Build.BRAND.replaceFirstChar(Char::uppercase),
                "Model" to Build.MODEL,
                "Codename" to Build.DEVICE,
                "Product" to Build.PRODUCT,
                "Board" to Build.BOARD,
                "Hardware" to Build.HARDWARE,
            )),
            Section("SYSTEM", R.drawable.ic_info, listOfNotNull(
                "Android version" to Build.VERSION.RELEASE,
                "API level" to Build.VERSION.SDK_INT.toString(),
                "Security patch" to Build.VERSION.SECURITY_PATCH,
                "Build ID" to Build.ID,
                "Build type" to Build.TYPE,
                "Kernel" to (System.getProperty("os.version") ?: "Unknown"),
                "Bootloader" to Build.BOOTLOADER,
                Build.getRadioVersion()?.takeIf { it.isNotBlank() }?.let { "Baseband" to it },
                "Language" to Locale.getDefault().displayName,
                "Time zone" to TimeZone.getDefault().id,
                "Uptime" to duration(SystemClock.elapsedRealtime()),
            )),
            Section("PROCESSOR", R.drawable.ic_cpu, listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != Build.UNKNOWN) {
                    "Chipset" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
                } else null,
                "Cores" to Runtime.getRuntime().availableProcessors().toString(),
                maxCpuFrequency()?.let { "Max frequency" to it },
                "Architecture" to Build.SUPPORTED_ABIS.joinToString(", "),
                "64-bit" to if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "Yes" else "No",
                "OpenGL ES" to getSystemService(ActivityManager::class.java).deviceConfigurationInfo.glEsVersion,
            )),
            Section("MEMORY & STORAGE", R.drawable.ic_memory, listOf(
                "Total RAM" to formatBytes(ramTotal),
                "Available RAM" to formatBytes(ramAvail),
                "RAM in use" to "${(ramTotal - ramAvail) * 100 / ramTotal.coerceAtLeast(1)}%",
                "Internal storage" to formatBytes(internal.totalBytes),
                "Free storage" to formatBytes(internal.availableBytes),
            )),
            Section("DISPLAY", R.drawable.ic_monitor, listOfNotNull(
                "Resolution" to "${metrics.widthPixels} × ${metrics.heightPixels} px",
                "Size" to String.format(Locale.US, "%.1f\"", inches),
                "Density" to "${metrics.densityDpi} dpi (${densityBucket(metrics.densityDpi)})",
                "Refresh rate" to if (rates.size > 1) rates.joinToString(" / ") { "$it Hz" } else "${display.refreshRate.toInt()} Hz",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "HDR" to if (display.isHdr) "Supported" else "No" else null,
            )),
            Section("CAMERAS", R.drawable.ic_camera, cameras()),
            Section("FEATURES", R.drawable.ic_wrench, features()),
        )
    }

    private fun maxCpuFrequency(): String? = runCatching {
        val max = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { core ->
            File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
        }.maxOrNull() ?: return null
        String.format(Locale.US, "%.2f GHz", max / 1_000_000.0)
    }.getOrNull()

    private fun densityBucket(dpi: Int) = when {
        dpi <= 120 -> "ldpi"; dpi <= 160 -> "mdpi"; dpi <= 240 -> "hdpi"
        dpi <= 320 -> "xhdpi"; dpi <= 480 -> "xxhdpi"; else -> "xxxhdpi"
    }

    private fun cameras(): List<Pair<String, String>> = runCatching {
        val cm = getSystemService(CameraManager::class.java)
        cm.cameraIdList.map { id ->
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                else -> "External"
            }
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val mp = size?.let { String.format(Locale.US, "%.1f MP", it.width * it.height / 1_000_000.0) } ?: "?"
            val flash = if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) " · flash" else ""
            "$facing camera $id" to "$mp$flash"
        }
    }.getOrDefault(emptyList()).ifEmpty { listOf("Cameras" to "None found") }

    private fun features(): List<Pair<String, String>> {
        fun has(feature: String) = if (packageManager.hasSystemFeature(feature)) "✓ Yes" else "✗ No"
        val sensors = getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL).size
        return listOf(
            "Bluetooth" to has(PackageManager.FEATURE_BLUETOOTH),
            "Bluetooth LE" to has(PackageManager.FEATURE_BLUETOOTH_LE),
            "Wi-Fi" to has(PackageManager.FEATURE_WIFI),
            "Wi-Fi Direct" to has(PackageManager.FEATURE_WIFI_DIRECT),
            "NFC" to has(PackageManager.FEATURE_NFC),
            "GPS" to has(PackageManager.FEATURE_LOCATION_GPS),
            "Fingerprint" to has(PackageManager.FEATURE_FINGERPRINT),
            "Infrared blaster" to has(PackageManager.FEATURE_CONSUMER_IR),
            "USB host (OTG)" to has(PackageManager.FEATURE_USB_HOST),
            "Telephony" to has(PackageManager.FEATURE_TELEPHONY),
            "Sensors" to "$sensors",
        )
    }

    private fun duration(ms: Long): String {
        val m = ms / 60_000
        val d = m / 1440
        val h = m / 60 % 24
        return if (d > 0) "${d}d ${h}h ${m % 60}m" else "${h}h ${m % 60}m"
    }

    private fun asText() = buildString {
        append("${Build.MANUFACTURER} ${Build.MODEL}\n")
        sections.forEach { s ->
            append("\n").append(s.title).append('\n')
            s.rows.forEach { (k, v) -> append("  ").append(k).append(": ").append(v).append('\n') }
        }
    }
}
