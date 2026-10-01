package com.truckcontroller.pro.speed

import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * Current GPS speed plus trip statistics (distance, time, max / average speed).
 *
 * Locations arrive from the Speedometer screen while it is visible and from [TripService]
 * while a trip is recorded; duplicates of the same fix are ignored. All calls happen on the
 * main thread. Trip stats are persisted so they survive the app being closed.
 */
object SpeedTracker {

    enum class TripState { IDLE, RUNNING, PAUSED }

    /** Below this (≈ 2.5 km/h) the phone is treated as standing still. */
    private const val STILL_MPS = 0.7f
    /** Fixes less accurate than this are shown but not used for distance / max speed. */
    private const val MAX_ACCURACY_M = 30f
    /** A fix older than this means "no GPS signal". */
    const val STALE_MS = 6_000L
    private const val MAX_GAP_MS = 15_000L

    private const val PREFS = "speedometer"

    // Live
    var speedMps = 0f
        private set
    var accuracyM = -1f
        private set
    var lastFixAt = 0L          // elapsedRealtime of the last fix
        private set
    val hasFix get() = lastFixAt != 0L && SystemClock.elapsedRealtime() - lastFixAt < STALE_MS
    /** Latest position, for the map. */
    var position: Location? = null
        private set

    // Trip
    var tripState = TripState.IDLE
        private set
    var distanceM = 0.0
        private set
    var maxSpeedMps = 0f
        private set
    var movingMs = 0L
        private set
    private var accumulatedMs = 0L      // trip time before the current running stretch
    private var runningSince = 0L       // elapsedRealtime when the current stretch started
    private var startedAtWall = 0L      // wall-clock start of the trip, for the history

    private var lastLocation: Location? = null
    private var lastFixNanos = 0L
    private var loaded = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    var useMph = false
        private set

    val elapsedMs: Long
        get() = accumulatedMs + if (tripState == TripState.RUNNING) SystemClock.elapsedRealtime() - runningSince else 0L

    /** Average over the whole trip time, including stops. */
    val averageMps: Float
        get() = if (elapsedMs > 0) (distanceM / (elapsedMs / 1000.0)).toFloat() else 0f

    /** Average while moving only. */
    val movingAverageMps: Float
        get() = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f

    val hasTripData get() = tripState != TripState.IDLE || distanceM > 0 || elapsedMs > 0

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
    private fun notifyListeners() = listeners.forEach { it() }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        useMph = p.getBoolean("useMph", false)
        tripState = runCatching { TripState.valueOf(p.getString("state", null) ?: "IDLE") }.getOrDefault(TripState.IDLE)
        distanceM = p.getFloat("distance", 0f).toDouble()
        maxSpeedMps = p.getFloat("max", 0f)
        movingMs = p.getLong("moving", 0L)
        accumulatedMs = p.getLong("accumulated", 0L)
        runningSince = p.getLong("runningSince", 0L)
        startedAtWall = p.getLong("startedAtWall", 0L)
        if (tripState == TripState.RUNNING && runningSince > SystemClock.elapsedRealtime()) {
            // The phone rebooted during the trip: keep what was recorded, as paused
            tripState = TripState.PAUSED
        }
    }

    private fun save(context: Context) {
        prefs(context).edit()
            .putString("state", tripState.name)
            .putFloat("distance", distanceM.toFloat())
            .putFloat("max", maxSpeedMps)
            .putLong("moving", movingMs)
            .putLong("accumulated", accumulatedMs)
            .putLong("runningSince", runningSince)
            .putLong("startedAtWall", startedAtWall)
            .apply()
    }

    fun setUseMph(context: Context, mph: Boolean) {
        useMph = mph
        prefs(context).edit().putBoolean("useMph", mph).apply()
        notifyListeners()
    }

    // ------------------------------------------------------------------
    // Location input
    // ------------------------------------------------------------------

    fun onLocation(context: Context, location: Location) {
        load(context)
        val fixNanos = location.elapsedRealtimeNanos
        if (fixNanos <= lastFixNanos) return // same fix delivered twice (screen + trip service)
        val previous = lastLocation
        val dtMs = if (lastFixNanos == 0L) 0L else (fixNanos - lastFixNanos) / 1_000_000
        lastFixNanos = fixNanos
        lastFixAt = SystemClock.elapsedRealtime()
        accuracyM = if (location.hasAccuracy()) location.accuracy else -1f
        val accurate = location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_M
        position = location

        var speed = when {
            location.hasSpeed() -> location.speed
            previous != null && dtMs in 1..MAX_GAP_MS -> previous.distanceTo(location) / (dtMs / 1000f)
            else -> 0f
        }
        if (speed < STILL_MPS) speed = 0f
        speedMps = speed

        if (tripState == TripState.RUNNING) {
            val gapOk = dtMs in 1..MAX_GAP_MS
            if (speed > 0f && gapOk) movingMs += dtMs
            // Only count distance while actually moving, so GPS drift at a standstill is ignored
            if (accurate && speed > 0f && gapOk && previous != null) distanceM += previous.distanceTo(location)
            if (accurate && reliableSpeed(location)) maxSpeedMps = max(maxSpeedMps, speed)
            if (accurate) TripRoute.record(context, location, speed)
            save(context)
        }
        lastLocation = if (accurate || previous == null) location else previous
        notifyListeners()
    }

    private fun reliableSpeed(location: Location): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            return location.speedAccuracyMetersPerSecond <= 3f
        }
        return true
    }

    /** Called by the screen's ticker so "no signal" and the running clock stay current. */
    fun tick() {
        if (lastFixAt != 0L && !hasFix) speedMps = 0f
        notifyListeners()
    }

    // ------------------------------------------------------------------
    // Trip control
    // ------------------------------------------------------------------

    fun startTrip(context: Context) {
        load(context)
        distanceM = 0.0
        maxSpeedMps = 0f
        movingMs = 0L
        accumulatedMs = 0L
        runningSince = SystemClock.elapsedRealtime()
        startedAtWall = System.currentTimeMillis()
        tripState = TripState.RUNNING
        lastLocation = null
        TripRoute.reset(context)
        save(context)
        notifyListeners()
    }

    fun pauseTrip(context: Context) {
        if (tripState != TripState.RUNNING) return
        accumulatedMs += SystemClock.elapsedRealtime() - runningSince
        tripState = TripState.PAUSED
        save(context)
        notifyListeners()
    }

    fun resumeTrip(context: Context) {
        if (tripState != TripState.PAUSED) return
        runningSince = SystemClock.elapsedRealtime()
        tripState = TripState.RUNNING
        lastLocation = null // don't bridge the paused gap with a straight line
        TripRoute.breakSegment(context)
        save(context)
        notifyListeners()
    }

    /**
     * Ends the trip and stores it in [TripHistory]. The stats stay visible as the last trip
     * until a new one starts.
     */
    fun endTrip(context: Context) {
        if (tripState == TripState.IDLE) return
        if (tripState == TripState.RUNNING) accumulatedMs += SystemClock.elapsedRealtime() - runningSince
        tripState = TripState.IDLE
        save(context)
        // Skip empty trips (started and ended without moving)
        if (distanceM >= 10.0 || elapsedMs >= 60_000L) {
            val now = System.currentTimeMillis()
            TripHistory.add(
                context,
                TripRecord(
                    id = now,
                    startedAt = if (startedAtWall > 0) startedAtWall else now - elapsedMs,
                    endedAt = now,
                    distanceM = distanceM,
                    elapsedMs = elapsedMs,
                    movingMs = movingMs,
                    maxSpeedMps = maxSpeedMps,
                )
            )
            TripRoute.saveForTrip(context, now)
        }
        notifyListeners()
    }

    fun clearTrip(context: Context) {
        tripState = TripState.IDLE
        distanceM = 0.0
        maxSpeedMps = 0f
        movingMs = 0L
        accumulatedMs = 0L
        TripRoute.reset(context)
        save(context)
        notifyListeners()
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    val speedUnit get() = if (useMph) "mph" else "km/h"
    val distanceUnit get() = if (useMph) "mi" else "km"

    fun toDisplaySpeed(mps: Float) = mps * if (useMph) 2.2369363f else 3.6f

    fun formatSpeed(mps: Float) = toDisplaySpeed(mps).let {
        if (it < 10f && it > 0f) String.format(Locale.US, "%.1f", it) else it.toInt().toString()
    }

    fun formatDistance(meters: Double): String {
        val value = meters / if (useMph) 1609.344 else 1000.0
        return String.format(Locale.US, if (value < 100) "%.2f" else "%.1f", value)
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
