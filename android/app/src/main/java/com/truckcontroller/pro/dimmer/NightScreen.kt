package com.truckcontroller.pro.dimmer

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Night Screen: a system-wide dimming overlay controlled from the app, the notification
 * and the Quick Settings tile. Settings are stored here and applied by [NightScreenService].
 */
object NightScreen {

    const val MIN_LEVEL = 10
    /** Android 12+ ignores touches through overlays darker than 80 %, so stay at or below it. */
    const val MAX_LEVEL = 80
    const val STEP = 10

    private const val PREFS = "night_screen"
    private const val KEY_LEVEL = "level"
    private const val KEY_WARM = "warm"

    @Volatile var isRunning = false
        internal set

    fun level(context: Context) =
        prefs(context).getInt(KEY_LEVEL, 50).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun isWarm(context: Context) = prefs(context).getBoolean(KEY_WARM, false)

    fun setLevel(context: Context, level: Int) {
        prefs(context).edit().putInt(KEY_LEVEL, level.coerceIn(MIN_LEVEL, MAX_LEVEL)).apply()
        if (isRunning) send(context, NightScreenService.ACTION_REFRESH)
    }

    fun setWarm(context: Context, warm: Boolean) {
        prefs(context).edit().putBoolean(KEY_WARM, warm).apply()
        if (isRunning) send(context, NightScreenService.ACTION_REFRESH)
    }

    /** Drawing over other apps must be granted in system settings before starting. */
    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context, Intent(context, NightScreenService::class.java).setAction(NightScreenService.ACTION_START)
        )
    }

    fun stop(context: Context) = send(context, NightScreenService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        context.startService(Intent(context, NightScreenService::class.java).setAction(action))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
