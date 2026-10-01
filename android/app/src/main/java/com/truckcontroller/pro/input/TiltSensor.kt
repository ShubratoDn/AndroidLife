package com.truckcontroller.pro.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Reads how far the phone is turned like a steering wheel: the rotation around the axis through
 * the screen, from gravity, in degrees (right turn positive). Works in either landscape direction;
 * holding the phone tilted back is fine, only lying flat gives no reading.
 */
class TiltSensor(private val context: Context, private val onAngle: (degrees: Float) -> Unit) : SensorEventListener {

    companion object {
        /** Below this much gravity in the screen plane the phone is too flat to read. */
        private const val MIN_IN_PLANE = 3f
        private const val SMOOTHING = 0.35f
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var started = false
    private var smoothed = Float.NaN

    /** Latest phone angle before any centre offset. */
    var angle = 0f
        private set

    val available get() = sensor != null

    fun start(): Boolean {
        val s = sensor ?: return false
        if (started) return true
        started = true
        smoothed = Float.NaN
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        // Gravity in the coordinates of the screen as it is currently shown
        val (sx, sy) = when (ContextCompat.getDisplayOrDefault(context).rotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        if (hypot(sx, sy) < MIN_IN_PLANE) return
        val raw = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
        smoothed = if (smoothed.isNaN()) raw else smoothed + (raw - smoothed) * (if (event.sensor.type == Sensor.TYPE_GRAVITY) 1f - SMOOTHING else 0.15f)
        angle = smoothed
        onAngle(smoothed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
