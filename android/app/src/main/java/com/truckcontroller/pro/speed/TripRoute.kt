package com.truckcontroller.pro.speed

import android.content.Context
import android.location.Location
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One recorded position. [segment] changes after a pause so the map doesn't join the gap. */
data class RoutePoint(val lat: Double, val lon: Double, val speedMps: Float, val time: Long, val segment: Int)

/**
 * GPS route of trips. While a trip runs its points are appended to a file as they arrive (so a
 * closed app or reboot loses nothing); a copy is kept for every saved trip so its route can be
 * shown on a map later or exported as GPX. All calls happen on the main thread.
 */
object TripRoute {

    /** A new point when moved this far ... */
    private const val MIN_STEP_M = 8f
    /** ... or after this long (keeps standing-still time visible on the route). */
    private const val MAX_INTERVAL_MS = 30_000L
    private const val DIR = "routes"
    private const val CURRENT = "current"

    private var points: MutableList<RoutePoint>? = null
    private var segment = 0

    /** Changes whenever the current route changes, so live views know when to redraw. */
    var version = 0
        private set

    /** The running (or last finished) trip's route. */
    fun current(context: Context): List<RoutePoint> = ensure(context)

    private fun ensure(context: Context): MutableList<RoutePoint> {
        points?.let { return it }
        val list = read(file(context, CURRENT)).toMutableList()
        points = list
        segment = list.lastOrNull()?.segment ?: 0
        return list
    }

    /** New trip: forget the previous route. */
    fun reset(context: Context) {
        points = mutableListOf()
        segment = 0
        file(context, CURRENT).delete()
        version++
    }

    /** After a pause the next point starts a new line. */
    fun breakSegment(context: Context) {
        val list = ensure(context)
        segment = (list.lastOrNull()?.segment ?: segment) + 1
    }

    fun record(context: Context, location: Location, speedMps: Float) {
        val list = ensure(context)
        val last = list.lastOrNull()
        if (last != null && last.segment == segment) {
            val distance = FloatArray(1)
            Location.distanceBetween(last.lat, last.lon, location.latitude, location.longitude, distance)
            if (distance[0] < MIN_STEP_M && location.time - last.time < MAX_INTERVAL_MS) return
        }
        val point = RoutePoint(location.latitude, location.longitude, speedMps, location.time, segment)
        list += point
        runCatching { file(context, CURRENT).appendText(line(point)) }
        version++
    }

    /** The trip was saved to the history: keep its route under the trip's id. */
    fun saveForTrip(context: Context, tripId: Long) {
        val source = file(context, CURRENT)
        if (source.length() > 0) runCatching { source.copyTo(file(context, tripId.toString()), overwrite = true) }
    }

    fun forTrip(context: Context, tripId: Long): List<RoutePoint> = read(file(context, tripId.toString()))

    fun hasRoute(context: Context, tripId: Long) = file(context, tripId.toString()).length() > 0

    fun delete(context: Context, tripId: Long) {
        file(context, tripId.toString()).delete()
    }

    /** Removes every saved trip's route (the running trip's stays). */
    fun deleteSaved(context: Context) {
        File(context.filesDir, DIR).listFiles()?.filter { it.name != "$CURRENT.csv" }?.forEach { it.delete() }
    }

    private fun file(context: Context, name: String) = File(File(context.filesDir, DIR).apply { mkdirs() }, "$name.csv")

    private fun line(p: RoutePoint) =
        String.format(Locale.US, "%.6f,%.6f,%.1f,%d,%d\n", p.lat, p.lon, p.speedMps, p.time, p.segment)

    private fun read(file: File): List<RoutePoint> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val f = line.split(',')
                if (f.size < 5) return@mapNotNull null
                RoutePoint(
                    f[0].toDoubleOrNull() ?: return@mapNotNull null,
                    f[1].toDoubleOrNull() ?: return@mapNotNull null,
                    f[2].toFloatOrNull() ?: 0f,
                    f[3].toLongOrNull() ?: 0L,
                    f[4].toIntOrNull() ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // GPX export
    // ------------------------------------------------------------------

    /** Writes the route as a GPX 1.1 file in the shared cache and returns a content URI for sharing. */
    fun exportGpx(context: Context, name: String, route: List<RoutePoint>): Uri {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val xml = StringBuilder()
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<gpx version=\"1.1\" creator=\"PhoneDeck\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            .append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        route.groupBy { it.segment }.toSortedMap().values.forEach { seg ->
            xml.append("    <trkseg>\n")
            seg.forEach { p ->
                xml.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon))
                if (p.time > 0) xml.append("<time>").append(iso.format(Date(p.time))).append("</time>")
                xml.append("</trkpt>\n")
            }
            xml.append("    </trkseg>\n")
        }
        xml.append("  </trk>\n</gpx>\n")
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name.replace(Regex("[^\\w .-]"), "_") + ".gpx")
        file.writeText(xml.toString())
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
