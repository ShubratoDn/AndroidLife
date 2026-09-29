package com.truckcontroller.pro.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R
import java.util.Locale

/**
 * GPS / GNSS test: sky plot, per-satellite signal bars, constellations, fix quality, time to first
 * fix and dual-frequency detection.
 */
class GpsTestActivity : HardwareTestActivity() {

    private lateinit var lm: LocationManager
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var sky: SkyPlotView
    private lateinit var bars: SnrBarsView
    private val rows = HashMap<String, TextView>()
    private lateinit var constellations: LinearLayout
    private var startedAt = 0L
    private var ttffMs: Int? = null
    private var lastFix: Location? = null
    private var dualFrequency = false
    private val handler = Handler(Looper.getMainLooper())

    override fun requiredPermissions() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    override val permissionReason = "Location permission is needed to receive GPS satellite data. Nothing is stored or sent."

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) = showSatellites(s)
        override fun onFirstFix(ttffMillis: Int) { ttffMs = ttffMillis }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastFix = location; showFix() }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val ticker = object : Runnable {
        override fun run() {
            showFix()
            handler.postDelayed(this, 1000)
        }
    }

    override fun buildTest() {
        lm = getSystemService(LocationManager::class.java)
        page.addCard(card().apply {
            status = statusText("Searching…")
            statusSub = smallText()
            addView(status)
            addView(statusSub)
            if (!lm.isLocationEnabled) {
                addView(buttonRow(actionButton("Turn on location") {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            }
        })

        page.addCard(titledCard("SKY VIEW", R.drawable.ic_satellite, accent).apply {
            sky = SkyPlotView(context)
            addView(sky, LinearLayout.LayoutParams(MATCH, dp(260)))
            addView(smallText("Filled dots are used for the position fix; rings are only visible. Centre = overhead."))
        })

        page.addCard(titledCard("SIGNAL STRENGTH (dB-Hz)", R.drawable.ic_activity, accent).apply {
            bars = SnrBarsView(context)
            addView(bars, LinearLayout.LayoutParams(MATCH, dp(140)))
            addView(smallText("Above 30 dB-Hz is good, above 40 is excellent. Faded bars aren't used in the fix."))
        })

        page.addCard(titledCard("POSITION", R.drawable.ic_map_pin, accent).apply {
            listOf("Satellites in view", "Used in fix", "Time to first fix", "Accuracy", "Latitude", "Longitude",
                "Altitude", "Speed", "Bearing", "Dual-frequency (L5/E5)").forEach { rows[it] = infoRow(it) }
        })

        constellations = titledCard("CONSTELLATIONS (USED / VISIBLE)", R.drawable.ic_radar, accent)
        page.addCard(constellations)
    }

    @SuppressLint("MissingPermission")
    override fun startListening() {
        startedAt = SystemClock.elapsedRealtime()
        runCatching {
            lm.registerGnssStatusCallback(gnssCallback, handler)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
        handler.post(ticker)
    }

    override fun stopListening() {
        lm.unregisterGnssStatusCallback(gnssCallback)
        lm.removeUpdates(locationListener)
        handler.removeCallbacks(ticker)
    }

    private fun showSatellites(s: GnssStatus) {
        val list = (0 until s.satelliteCount).map { i ->
            Satellite(
                s.getConstellationType(i), s.getSvid(i), s.getCn0DbHz(i), s.getElevationDegrees(i),
                s.getAzimuthDegrees(i), s.usedInFix(i),
                if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i) / 1e6f else null,
            )
        }
        sky.satellites = list
        bars.satellites = list
        if (list.any { it.carrierMhz != null && it.carrierMhz < 1300f }) dualFrequency = true
        val used = list.count { it.usedInFix }
        rows["Satellites in view"]?.text = "${list.size}"
        rows["Used in fix"]?.apply {
            text = "$used"
            setTextColor(hex(when { used >= 8 -> "#34D399"; used >= 4 -> "#FBBF24"; else -> "#F87171" }))
        }
        rows["Dual-frequency (L5/E5)"]?.apply {
            if (dualFrequency) { text = "Yes"; setTextColor(hex("#34D399")) }
            else if (list.any { it.carrierMhz != null }) { text = "Not seen (L1 only)"; setTextColor(Color.WHITE) }
            else { text = "Not reported"; setTextColor(Color.WHITE) }
        }

        constellations.removeViews(1, constellations.childCount - 1)
        list.groupBy { it.constellation }.toSortedMap().forEach { (c, sats) ->
            constellations.infoRow(constellationName(c), "${sats.count { it.usedInFix }} / ${sats.size}")
                .setTextColor(constellationColor(c))
        }
    }

    private fun showFix() {
        val fix = lastFix?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 10_000_000_000L }
        val waited = (SystemClock.elapsedRealtime() - startedAt) / 1000
        if (fix == null) {
            status.text = "Searching…"
            status.setTextColor(hex("#FBBF24"))
            statusSub.text = "Waiting for a position for ${waited}s. Go near a window or outside."
        } else {
            val acc = fix.accuracy
            status.text = if (fix.hasAltitude()) "3D fix" else "2D fix"
            status.setTextColor(hex(when { acc <= 10 -> "#34D399"; acc <= 30 -> "#FBBF24"; else -> "#F87171" }))
            statusSub.text = when {
                acc <= 5 -> "Excellent accuracy"; acc <= 10 -> "Good accuracy"; acc <= 30 -> "Fair accuracy"
                else -> "Poor accuracy — move to open sky"
            }
            rows["Accuracy"]?.text = String.format(Locale.US, "± %.1f m", acc)
            rows["Latitude"]?.text = String.format(Locale.US, "%.6f°", fix.latitude)
            rows["Longitude"]?.text = String.format(Locale.US, "%.6f°", fix.longitude)
            rows["Altitude"]?.text = if (fix.hasAltitude()) String.format(Locale.US, "%.0f m", fix.altitude) else "—"
            rows["Speed"]?.text = if (fix.hasSpeed()) String.format(Locale.US, "%.1f km/h", fix.speed * 3.6f) else "—"
            rows["Bearing"]?.text = if (fix.hasBearing()) String.format(Locale.US, "%.0f°", fix.bearing) else "—"
        }
        rows["Time to first fix"]?.text = ttffMs?.let { String.format(Locale.US, "%.1f s", it / 1000f) } ?: "Waiting…"
    }
}
