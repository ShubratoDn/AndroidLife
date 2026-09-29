package com.truckcontroller.pro.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * High-definition haptic feedback engine for ETS2 truck physical controls.
 */
class HapticFeedbackHelper(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var isEnabled: Boolean = true
    var intensityFactor: Float = 1.0f

    private val canVibrate: Boolean
        get() = isEnabled && intensityFactor > 0f && vibrator.hasVibrator()

    private fun amp(value: Int) = (value * intensityFactor).toInt().coerceIn(1, 255)

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        if (!canVibrate) return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes.map { if (it == 0) 0 else amp(it) }.toIntArray(), -1))
    }

    private fun oneShot(ms: Long, amplitude: Int) {
        if (!canVibrate) return
        vibrator.vibrate(VibrationEffect.createOneShot(ms, amp(amplitude)))
    }

    /** Mechanical transmission shift clunk (dual impulse) */
    fun performGearShiftHaptic() = waveform(longArrayOf(0, 25, 20, 45), intArrayOf(0, 255, 50, 200))

    /** Heavy pneumatic air brake pop */
    fun performAirBrakeHaptic() = waveform(longArrayOf(0, 60, 25, 30), intArrayOf(0, 240, 0, 160))

    /** Diesel starter crank followed by idle catch */
    fun performEngineStartHaptic() =
        waveform(longArrayOf(0, 40, 40, 40, 40, 40, 40, 120), intArrayOf(0, 180, 60, 200, 60, 220, 60, 255))

    /** Air horn buzz */
    fun performHornHaptic() = oneShot(60, 150)

    /** CB push-to-talk key-up */
    fun performRadioPttHaptic() = waveform(longArrayOf(0, 15, 30, 15), intArrayOf(0, 200, 0, 200))

    /** Tactile micro-click for cockpit buttons */
    fun performButtonClickHaptic() {
        if (!canVibrate) return
        if (intensityFactor >= 0.5f) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            oneShot(12, 180)
        }
    }

    /** Steering lock endpoint hit (vibrates when reaching full lock left or right) */
    fun performWheelLockHaptic() = oneShot(35, 255)
}
