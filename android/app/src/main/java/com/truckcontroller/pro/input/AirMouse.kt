package com.truckcontroller.pro.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Turns phone rotation into pointer motion. Left / right is the turn around the vertical (from
 * gravity), so it works whether the phone is held flat like a remote or upright; up / down is the
 * tilt of the top edge. Small rotations are ignored so the pointer stays still in a steady hand.
 */
class AirMouse(context: Context, private val onMove: (dx: Int, dy: Int) -> Unit) : SensorEventListener {

    companion object {
        /** Pointer pixels per radian of rotation at speed 1. */
        private const val PIXELS_PER_RADIAN = 900f
        /** Hand tremor below this rate (rad/s) is ignored. */
        private const val DEADBAND = 0.02f
        private const val SMOOTHING = 0.45f
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available get() = gyroscope != null

    /** 0.3 .. 3 */
    var speed = 1f
    /** Moves the pointer only while true (finger on the pad, or "always on"). */
    var active = false
        set(value) {
            field = value
            if (!value) {
                smoothX = 0f
                smoothY = 0f
                restX = 0f
                restY = 0f
            }
        }

    /** World "up" in phone coordinates. */
    private val up = floatArrayOf(0f, 0f, 1f)
    private var lastTimestamp = 0L
    private var smoothX = 0f
    private var smoothY = 0f
    private var restX = 0f
    private var restY = 0f
    private var started = false

    fun start(): Boolean {
        val gyro = gyroscope ?: return false
        if (started) return true
        started = true
        lastTimestamp = 0L
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        gravitySensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        active = false
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val v = event.values
                val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (n < 1f) return
                // The raw accelerometer also sees hand motion: follow it slowly
                val k = if (event.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
                for (i in 0..2) up[i] += (v[i] / n - up[i]) * k
            }
            Sensor.TYPE_GYROSCOPE -> onRotation(event)
        }
    }

    private fun onRotation(event: SensorEvent) {
        val dt = if (lastTimestamp == 0L) 0f else ((event.timestamp - lastTimestamp) / 1e9f).coerceIn(0f, 0.05f)
        lastTimestamp = event.timestamp
        if (!active || dt == 0f) return
        val (gx, gy, gz) = event.values
        val yaw = gx * up[0] + gy * up[1] + gz * up[2]   // turning left is positive
        val pitch = gx                                   // top edge up is positive
        smoothX += (-deadband(yaw) - smoothX) * (1f - SMOOTHING)
        smoothY += (-deadband(pitch) - smoothY) * (1f - SMOOTHING)
        // Quick flicks travel further than slow aiming
        fun gain(rate: Float) = PIXELS_PER_RADIAN * speed * (1f + abs(rate) * 0.6f)
        restX += smoothX * dt * gain(smoothX)
        restY += smoothY * dt * gain(smoothY)
        val dx = restX.toInt()
        val dy = restY.toInt()
        if (dx != 0 || dy != 0) {
            restX -= dx
            restY -= dy
            onMove(dx, dy)
        }
    }

    private fun deadband(rate: Float) = if (abs(rate) < DEADBAND) 0f else rate - sign(rate) * DEADBAND

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
