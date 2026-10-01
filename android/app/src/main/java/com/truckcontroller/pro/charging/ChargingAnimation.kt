package com.truckcontroller.pro.charging

import android.content.Context
import android.net.Uri
import android.os.Bundle

/** Built-in lock screen charging animations, drawn live by [ChargeAnimationView]. */
enum class ChargeStyle(val title: String, val subtitle: String) {
    NEON_RING("Neon Ring", "Glowing ring, particles flow in"),
    LIQUID("Liquid Battery", "Wavy liquid fills with bubbles"),
    ORB("Energy Orb", "Plasma orb with lightning arcs"),
    GAUGE("Truck Gauge", "Speedometer in cockpit orange"),
    AURORA("Aurora", "Northern lights rise as it fills"),
    HEARTBEAT("Heartbeat", "ECG pulse, faster with more watts"),
    DIGITAL_RAIN("Digital Rain", "Falling code piles up to the level"),
    HEX_SHIELD("Hex Shield", "Honeycomb lights up cell by cell"),
}

/**
 * Charging Animation (rooted phones with LSPosed): PhoneDeck's hook inside System UI shows the
 * chosen [ChargeStyle] instead of the HyperOS charging animation. Settings live here and reach
 * System UI through [ChargingSettingsProvider], so changes apply on the next plug-in.
 */
object ChargingAnimation {

    const val AUTHORITY = "com.truckcontroller.pro.charging"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    const val METHOD_SETTINGS = "settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_STYLE = "style"
    const val KEY_DETAILS = "details"
    const val KEY_STAY_ON = "stay_on"

    /** How long the animation shows normally. */
    const val SHOW_SECONDS = 30
    /** How long it shows with "Show for 5 minutes" on. */
    const val STAY_ON_MINUTES = 5

    private const val PREFS = "charging_animation"

    /** Replaced with `true` by PhoneDeck's own LSPosed hook when the module is active. */
    fun isModuleActive(): Boolean = false

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun style(context: Context): ChargeStyle =
        prefs(context).getString(KEY_STYLE, null)?.let { name -> ChargeStyle.entries.firstOrNull { it.name == name } }
            ?: ChargeStyle.NEON_RING
    fun setStyle(context: Context, style: ChargeStyle) = prefs(context).edit().putString(KEY_STYLE, style.name).apply()

    /** Show charging speed (W, mA) and time to full under the level. */
    fun showDetails(context: Context) = prefs(context).getBoolean(KEY_DETAILS, true)
    fun setShowDetails(context: Context, show: Boolean) = prefs(context).edit().putBoolean(KEY_DETAILS, show).apply()

    /** Show for [STAY_ON_MINUTES] instead of [SHOW_SECONDS] (tap, unlock, a button or unplug still close it). */
    fun stayOn(context: Context) = prefs(context).getBoolean(KEY_STAY_ON, false)
    fun setStayOn(context: Context, stay: Boolean) = prefs(context).edit().putBoolean(KEY_STAY_ON, stay).apply()

    fun toBundle(context: Context) = Bundle().apply {
        putBoolean(KEY_ENABLED, isEnabled(context))
        putString(KEY_STYLE, style(context).name)
        putBoolean(KEY_DETAILS, showDetails(context))
        putBoolean(KEY_STAY_ON, stayOn(context))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
