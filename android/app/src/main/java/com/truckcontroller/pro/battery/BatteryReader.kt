package com.truckcontroller.pro.battery

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/** How the battery is doing, which also picks the screen colour (like Ampere). */
enum class ChargeState {
    CHARGING,
    DISCHARGING,
    FULL,
    /** Plugged in but the phone is not charging (battery protection, weak or incompatible charger). */
    NOT_CHARGING,
    PROBLEM,
}

/** One snapshot of everything the Charging Meter shows. */
data class BatterySnapshot(
    val currentMa: Int?,          // averaged; + charging, − discharging; null while measuring
    val rawCurrentMa: Int?,       // latest single reading
    val minMa: Int?,
    val maxMa: Int?,
    val state: ChargeState,
    val statusText: String,
    val plugged: String,
    val level: Int,
    val health: String,
    val technology: String,
    val temperatureC: Float,
    val voltageV: Float,
    val powerW: Float?,
    val chargeMah: Int?,
    val estimatedCapacityMah: Int?,
    val timeToFullMs: Long?,
    val cycleCount: Int?,
    val problem: String?,         // e.g. "Over voltage", "Overheat"
)

/**
 * Reads battery current and state. Phone makers report current in µA or mA and with either sign,
 * so the unit and sign are learned from the phone's own readings and remembered.
 */
class BatteryReader(private val context: Context) {

    private val manager = context.getSystemService(BatteryManager::class.java)
    private val prefs = context.getSharedPreferences("charging_meter", Context.MODE_PRIVATE)

    /** 1 = µA (standard), 1000 = the phone reports mA. */
    private var unitFactor = prefs.getInt("unitFactor", 0)
    /** 1 = positive means charging (standard), -1 = inverted. 0 = not learned yet. */
    private var signFactor = prefs.getInt("signFactor", 0)

    private val window = ArrayDeque<Int>()
    private var min: Int? = null
    private var max: Int? = null
    private var lastState: ChargeState? = null
    private var lastPlugged: String? = null

    /** Reading history for the graph (mA, oldest first). */
    val history = ArrayDeque<Int>()

    companion object {
        const val AVERAGE_OF = 5
        const val HISTORY_SIZE = 180 // 3 minutes at 1 sample/s
    }

    fun resetStats() {
        window.clear()
        min = null
        max = null
    }

    fun sample(battery: Intent?): BatterySnapshot {
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedCode = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val healthCode = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else null
        } ?: manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val rawVoltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltage = if (rawVoltage in 1..99) rawVoltage.toFloat() else rawVoltage / 1000f // some report volts
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = pluggedText(pluggedCode)
        val isPlugged = pluggedCode != 0

        val problem = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Battery dead"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Battery failure"
            else -> null
        }
        val state = when {
            problem != null -> ChargeState.PROBLEM
            status == BatteryManager.BATTERY_STATUS_FULL || (isPlugged && level >= 100) -> ChargeState.FULL
            status == BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.CHARGING
            isPlugged -> ChargeState.NOT_CHARGING
            else -> ChargeState.DISCHARGING
        }

        // Plugging / unplugging starts a new measurement, like Ampere
        if (state != lastState || plugged != lastPlugged) {
            if (lastState != null) {
                resetStats()
                history.clear()
            }
            lastState = state
            lastPlugged = plugged
        }

        val raw = readCurrentMa(isPlugged, state)
        if (raw != null) {
            window.addLast(raw)
            while (window.size > AVERAGE_OF) window.removeFirst()
            history.addLast(raw)
            while (history.size > HISTORY_SIZE) history.removeFirst()
        }
        val average = if (window.size >= 3) window.average().roundToInt() else null
        if (average != null) {
            min = min?.let { minOf(it, average) } ?: average
            max = max?.let { maxOf(it, average) } ?: average
        }

        val chargeUah = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeMah = if (chargeUah > 0 && chargeUah != Int.MIN_VALUE) chargeUah / 1000 else null
        val capacity = if (chargeMah != null && level in 10..100) chargeMah * 100 / level else null
        val toFull = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && state == ChargeState.CHARGING) {
            manager.computeChargeTimeRemaining().takeIf { it > 0 }
        } else null
        val cycles = battery?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }

        return BatterySnapshot(
            currentMa = average,
            rawCurrentMa = raw,
            minMa = min,
            maxMa = max,
            state = state,
            statusText = statusText(status),
            plugged = plugged,
            level = level,
            health = healthText(healthCode),
            technology = battery?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty().ifEmpty { "Unknown" },
            temperatureC = temperature,
            voltageV = voltage,
            powerW = average?.let { abs(it) / 1000f * voltage },
            chargeMah = chargeMah,
            estimatedCapacityMah = capacity,
            timeToFullMs = toFull,
            cycleCount = cycles,
            problem = problem,
        )
    }

    /** Current in mA: + into the battery, − out of it. Learns this phone's unit and sign. */
    private fun readCurrentMa(plugged: Boolean, state: ChargeState): Int? {
        val raw = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null

        // Unit: on battery with the screen on a phone draws at least ~50 mA = 50 000 µA.
        // A reading below 5 000 while unplugged means the phone reports mA.
        if (unitFactor == 0) {
            if (!plugged) {
                unitFactor = if (abs(raw) < 5_000) 1000 else 1
                prefs.edit().putInt("unitFactor", unitFactor).apply()
            }
        }
        val factor = if (unitFactor == 0) (if (abs(raw) < 5_000) 1000 else 1) else unitFactor
        val ma = if (factor == 1000) raw else raw / 1000

        // Sign: current must leave the battery while unplugged; learn and remember the convention
        if (!plugged && signFactor == 0) {
            signFactor = if (ma > 0) -1 else 1
            prefs.edit().putInt("signFactor", signFactor).apply()
        }
        val sign = when {
            signFactor != 0 -> signFactor
            // Not learned yet: while charging assume the reading means "in"
            state == ChargeState.CHARGING -> if (ma < 0) -1 else 1
            else -> if (ma > 0) -1 else 1
        }
        return ma * sign
    }

    private fun pluggedText(code: Int) = when (code) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> "On battery"
    }

    private fun statusText(code: Int) = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun healthText(code: Int) = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
}
