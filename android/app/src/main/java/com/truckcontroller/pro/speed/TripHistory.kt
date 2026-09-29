package com.truckcontroller.pro.speed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A finished trip, stored in SI units (metres, milliseconds, m/s). */
data class TripRecord(
    val id: Long,
    val startedAt: Long,      // wall-clock ms
    val endedAt: Long,        // wall-clock ms
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val maxSpeedMps: Float,
) {
    val averageMps get() = if (elapsedMs > 0) (distanceM / (elapsedMs / 1000.0)).toFloat() else 0f
    val movingAverageMps get() = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
}

/** Saved trips, newest first, kept in app storage (survives restarts and updates). */
object TripHistory {

    private const val PREFS = "trip_history"
    private const val KEY = "trips"
    private const val MAX_TRIPS = 500

    fun all(context: Context): List<TripRecord> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                array.getJSONObject(i).run {
                    TripRecord(
                        id = getLong("id"),
                        startedAt = getLong("startedAt"),
                        endedAt = getLong("endedAt"),
                        distanceM = getDouble("distanceM"),
                        elapsedMs = getLong("elapsedMs"),
                        movingMs = getLong("movingMs"),
                        maxSpeedMps = getDouble("maxSpeedMps").toFloat(),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, trip: TripRecord) = save(context, (listOf(trip) + all(context)).take(MAX_TRIPS))

    fun delete(context: Context, id: Long) = save(context, all(context).filterNot { it.id == id })

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, trips: List<TripRecord>) {
        val array = JSONArray()
        trips.forEach { t ->
            array.put(JSONObject().apply {
                put("id", t.id)
                put("startedAt", t.startedAt)
                put("endedAt", t.endedAt)
                put("distanceM", t.distanceM)
                put("elapsedMs", t.elapsedMs)
                put("movingMs", t.movingMs)
                put("maxSpeedMps", t.maxSpeedMps.toDouble())
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
