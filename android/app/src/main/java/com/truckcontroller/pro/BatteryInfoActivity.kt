package com.truckcontroller.pro

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.truckcontroller.pro.battery.BatteryReader
import com.truckcontroller.pro.battery.BatterySnapshot
import com.truckcontroller.pro.battery.ChargeState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Battery Information: status, health, temperature, voltage, capacity (design vs. estimated,
 * wear), cycle count, charger rating and time estimates. Updates every 2 seconds.
 */
class BatteryInfoActivity : ToolActivity() {

    private lateinit var reader: BatteryReader
    private val handler = Handler(Looper.getMainLooper())
    private val rows = HashMap<String, TextView>()
    private lateinit var heroIcon: ImageView
    private lateinit var heroLevel: TextView
    private lateinit var heroStatus: TextView
    private var designCapacity: Int? = null

    private val ticker = object : Runnable {
        override fun run() {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            render(reader.sample(battery), battery)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        designCapacity = readDesignCapacity()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        list.addView(heroCard())
        list.addSection("STATUS", R.drawable.ic_battery,
            "Status", "Power source", "Level", "Health", "Temperature", "Voltage", "Technology")
        list.addSection("CAPACITY", R.drawable.ic_battery_full,
            "Charge now", "Estimated capacity", "Design capacity", "Battery wear", "Cycle count")
        list.addSection("CURRENT & CHARGER", R.drawable.ic_zap,
            "Current", "Power", "Time estimate", "Charger max current", "Charger max voltage")
        list.addView(TextView(this).apply {
            text = "Open Charging Meter"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1FB39A"))
            }
            setOnClickListener {
                haptics.performButtonClickHaptic()
                startActivity(Intent(this@BatteryInfoActivity, BatteryActivity::class.java))
            }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(16) })

        setContentView(toolPage("Battery Info", R.drawable.ic_battery, Color.parseColor("#22C55E"),
            ScrollView(this).apply { addView(list) },
            R.drawable.ic_share to { shareText("Share battery info", asText()) }))
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    private fun LinearLayout.addSection(title: String, icon: Int, vararg keys: String) {
        val card = titledCard(title, icon, Color.parseColor("#22C55E"))
        keys.forEach { rows[it] = card.infoRow(it) }
        addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    private fun heroCard() = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        heroIcon = ImageView(context).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setColorFilter(Color.WHITE)
        }
        addView(heroIcon, LinearLayout.LayoutParams(dp(62), dp(62)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            heroLevel = TextView(context).apply {
                textSize = 34f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            heroStatus = TextView(context, null, 0, R.style.Cockpit_Mono).apply { textSize = 13f }
            addView(heroLevel)
            addView(heroStatus)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) })
    }

    private fun stateColor(state: ChargeState) = Color.parseColor(when (state) {
        ChargeState.CHARGING -> "#22C55E"
        ChargeState.DISCHARGING -> "#F59E0B"
        ChargeState.FULL -> "#38BDF8"
        ChargeState.NOT_CHARGING -> "#A78BFA"
        ChargeState.PROBLEM -> "#EF4444"
    })

    private fun render(s: BatterySnapshot, battery: Intent?) {
        val c = stateColor(s.state)
        heroIcon.setImageResource(when (s.state) {
            ChargeState.CHARGING -> R.drawable.ic_battery_charging
            ChargeState.FULL -> R.drawable.ic_battery_full
            ChargeState.PROBLEM -> R.drawable.ic_battery_warning
            else -> R.drawable.ic_battery
        })
        heroIcon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        heroLevel.text = "${s.level}%"
        heroStatus.text = "${s.statusText} · ${s.plugged}"
        heroStatus.setTextColor(c)

        set("Status", s.statusText)
        set("Power source", s.plugged)
        set("Level", "${s.level}%")
        set("Health", s.health, if (s.problem != null) "#EF4444" else "#22C55E")
        set("Temperature", String.format(Locale.US, "%.1f °C", s.temperatureC), when {
            s.temperatureC < 38 -> "#22C55E"; s.temperatureC < 43 -> "#F59E0B"; else -> "#EF4444"
        })
        set("Voltage", String.format(Locale.US, "%.3f V", s.voltageV))
        set("Technology", s.technology)

        set("Charge now", s.chargeMah?.let { "$it mAh" } ?: "Not reported")
        set("Estimated capacity", s.estimatedCapacityMah?.let { "≈ $it mAh" } ?: "Not reported")
        set("Design capacity", designCapacity?.let { "$it mAh" } ?: "Not reported")
        val wear = if (designCapacity != null && s.estimatedCapacityMah != null) {
            (100 - s.estimatedCapacityMah * 100 / designCapacity!!).coerceAtLeast(0)
        } else null
        set("Battery wear", wear?.let { "≈ $it%" } ?: "—", when {
            wear == null -> null; wear < 20 -> "#22C55E"; wear < 35 -> "#F59E0B"; else -> "#EF4444"
        })
        set("Cycle count", s.cycleCount?.toString() ?: "Not reported (needs Android 14)")

        set("Current", s.currentMa?.let { if (it > 0) "+$it mA" else "$it mA" } ?: "Measuring…")
        set("Power", s.powerW?.let { String.format(Locale.US, "%.2f W", it) } ?: "—")
        set("Time estimate", timeEstimate(s))
        val maxCurrent = battery?.getIntExtra("max_charging_current", 0) ?: 0
        val maxVoltage = battery?.getIntExtra("max_charging_voltage", 0) ?: 0
        set("Charger max current", if (maxCurrent > 0) "${maxCurrent / 1000} mA" else "—")
        set("Charger max voltage", if (maxVoltage > 0) String.format(Locale.US, "%.1f V", maxVoltage / 1_000_000.0) else "—")
    }

    private fun timeEstimate(s: BatterySnapshot): String {
        s.timeToFullMs?.let { return "Full in ${formatMinutes(it / 60_000)}" }
        val current = s.currentMa ?: return "Measuring…"
        val charge = s.chargeMah ?: return "—"
        return if (s.state == ChargeState.DISCHARGING && current < -20) {
            "≈ ${formatMinutes((charge * 60L) / abs(current))} left at this rate"
        } else "—"
    }

    private fun formatMinutes(m: Long) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    private fun set(key: String, value: String, colorHex: String? = null) {
        rows[key]?.apply {
            text = value
            setTextColor(colorHex?.let { Color.parseColor(it) } ?: Color.WHITE)
        }
    }

    /** Factory battery size from the system power profile (not exposed by a public API). */
    @SuppressLint("PrivateApi")
    private fun readDesignCapacity(): Int? = runCatching {
        val profile = Class.forName("com.android.internal.os.PowerProfile")
            .getConstructor(android.content.Context::class.java).newInstance(this)
        val mah = profile.javaClass.getMethod("getBatteryCapacity").invoke(profile) as Double
        mah.roundToInt().takeIf { it in 500..30000 }
    }.getOrNull()

    private fun asText() = buildString {
        append("Battery information\n")
        rows.forEach { (k, v) -> append(k).append(": ").append(v.text).append('\n') }
    }
}
