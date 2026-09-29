package com.truckcontroller.pro.model

import android.content.Context

val WHEEL_ANGLES = intArrayOf(360, 540, 900, 1080, 1800)

data class ControllerSettings(
    val wheelMaxAngle: Int = 900,
    val autoCenterSpring: Int = 75,          // 0 to 100
    val deadzone: Int = 2,                   // 0 to 15 %
    val nonLinearity: Float = 1.2f,          // 1.0 to 2.5
    val hapticsEnabled: Boolean = true,
    val hapticIntensity: Int = 85,           // 0 to 100
    val soundEnabled: Boolean = true,
    val soundVolume: Int = 80,               // 0 to 100
    val shifterMode: ShifterMode = ShifterMode.SEQUENTIAL,
)

/**
 * Persists [ControllerSettings] in SharedPreferences.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("controller_settings", Context.MODE_PRIVATE)

    fun load(): ControllerSettings {
        val defaults = ControllerSettings()
        return ControllerSettings(
            wheelMaxAngle = prefs.getInt("wheelMaxAngle", defaults.wheelMaxAngle),
            autoCenterSpring = prefs.getInt("autoCenterSpring", defaults.autoCenterSpring),
            deadzone = prefs.getInt("deadzone", defaults.deadzone),
            nonLinearity = prefs.getFloat("nonLinearity", defaults.nonLinearity),
            hapticsEnabled = prefs.getBoolean("hapticsEnabled", defaults.hapticsEnabled),
            hapticIntensity = prefs.getInt("hapticIntensity", defaults.hapticIntensity),
            soundEnabled = prefs.getBoolean("soundEnabled", defaults.soundEnabled),
            soundVolume = prefs.getInt("soundVolume", defaults.soundVolume),
            shifterMode = runCatching {
                ShifterMode.valueOf(prefs.getString("shifterMode", null) ?: defaults.shifterMode.name)
            }.getOrDefault(defaults.shifterMode),
        )
    }

    fun save(settings: ControllerSettings) {
        prefs.edit()
            .putInt("wheelMaxAngle", settings.wheelMaxAngle)
            .putInt("autoCenterSpring", settings.autoCenterSpring)
            .putInt("deadzone", settings.deadzone)
            .putFloat("nonLinearity", settings.nonLinearity)
            .putBoolean("hapticsEnabled", settings.hapticsEnabled)
            .putInt("hapticIntensity", settings.hapticIntensity)
            .putBoolean("soundEnabled", settings.soundEnabled)
            .putInt("soundVolume", settings.soundVolume)
            .putString("shifterMode", settings.shifterMode.name)
            .apply()
    }
}
