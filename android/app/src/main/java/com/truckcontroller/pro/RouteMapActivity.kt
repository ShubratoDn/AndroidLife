package com.truckcontroller.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.speed.RoutePoint
import com.truckcontroller.pro.speed.SpeedTracker
import com.truckcontroller.pro.speed.SpeedTracker.TripState
import com.truckcontroller.pro.speed.TripHistory
import com.truckcontroller.pro.speed.TripRecord
import com.truckcontroller.pro.speed.TripRoute
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.hypot

/**
 * Trip route on OpenStreetMap.
 * - Live (no trip id): the running trip's route grows as you drive, with a dot that follows you.
 * - Saved trip: the whole route coloured by speed, start / end markers; tap the line for the
 *   speed and time there. Both can be shared as a GPX file.
 * Without internet the map tiles stay blank but the route is still drawn.
 */
class RouteMapActivity : ToolActivity() {

    companion object {
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val PREFS = "route_map"
        private const val KEY_DARK = "dark"

        fun live(context: Context) = Intent(context, RouteMapActivity::class.java)
        fun trip(context: Context, id: Long) = Intent(context, RouteMapActivity::class.java).putExtra(EXTRA_TRIP_ID, id)

        /** Speed bands: slow → fast. */
        private val BAND_COLORS = intArrayOf(
            Color.parseColor("#34D399"), Color.parseColor("#A3E635"),
            Color.parseColor("#FBBF24"), Color.parseColor("#F87171"),
        )
    }

    private val accent = Color.parseColor("#34D399")
    private var tripId = 0L
    private var trip: TripRecord? = null
    private var route: List<RoutePoint> = emptyList()
    private val isLive get() = tripId == 0L

    private lateinit var map: MapView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var pointInfo: TextView
    private lateinit var legend: LinearLayout
    private lateinit var recenter: ImageButton
    private lateinit var darkButton: ImageButton

    // Live
    private lateinit var locationManager: LocationManager
    private var listening = false
    private val liveLines = mutableListOf<Polyline>()
    private var drawnCount = 0
    private var drawnVersion = -1
    private var follow = true
    private var centered = false
    private var positionMarker: Marker? = null
    private var selectedMarker: Marker? = null
    private val locationListener = LocationListener { SpeedTracker.onLocation(this, it) }
    private val trackerListener: () -> Unit = { renderLive() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupOsmdroid()
        tripId = intent.getLongExtra(EXTRA_TRIP_ID, 0L)
        locationManager = getSystemService(LocationManager::class.java)
        if (isLive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            SpeedTracker.load(this)
        } else {
            trip = TripHistory.all(this).firstOrNull { it.id == tripId }
            route = TripRoute.forTrip(this, tripId)
        }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
            // Panning by hand stops following the position until "recenter"
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_MOVE) setFollow(false)
                false
            }
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!isLive) showNearest(p)
                return false
            }
            override fun longPressHelper(p: GeoPoint) = false
        }))
        applyDark()

        val frame = FrameLayout(this)
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        recenter = roundButton(R.drawable.ic_locate, "Follow my position") {
            setFollow(true)
            renderLive()
        }
        frame.addView(recenter, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        frame.addView(infoCard(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })

        val heading = if (isLive) "Live route" else trip?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.startedAt)) } ?: "Trip route"
        setContentView(toolPage(heading, R.drawable.ic_route, accent, frame,
            R.drawable.ic_share to { shareGpx() },
            R.drawable.ic_moon to { toggleDark() },
        ))
        darkButton = findDarkButton(frame)

        if (isLive) setupLive() else drawTrip()
    }

    override fun onStart() {
        super.onStart()
        if (isLive) {
            SpeedTracker.addListener(trackerListener)
            startListening()
            renderLive()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isLive) {
            SpeedTracker.removeListener(trackerListener)
            stopListening()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        map.onDetach()
    }

    /** Tiles go to the app's cache (no storage permission); OpenStreetMap requires an app user agent. */
    private fun setupOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        config.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles")
        config.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun infoCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#E6111620"))
            setStroke(dp(1), Color.parseColor("#1E293B"))
        }
        title = TextView(context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        detail = TextView(context, null, 0, R.style.Cockpit_Mono).apply {
            textSize = 12f
            setTextColor(color(R.color.slate_300))
        }
        pointInfo = TextView(context, null, 0, R.style.Cockpit_Mono).apply {
            textSize = 12f
            setTextColor(color(R.color.cyan_400))
            visibility = View.GONE
        }
        legend = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(title)
        addView(detail, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(legend, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(pointInfo, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun roundButton(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        contentDescription = description
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E6111620"))
            setStroke(dp(1), Color.parseColor("#334155"))
        }
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
    }

    /** The header's moon / sun button (second extra action). */
    private fun findDarkButton(frame: View): ImageButton {
        val header = (frame.parent as LinearLayout).getChildAt(0) as LinearLayout
        val buttons = (0 until header.childCount).map { header.getChildAt(it) }.filterIsInstance<ImageButton>()
        // home, share, moon, fullscreen
        return buttons[2].also { renderDarkButton(it) }
    }

    private fun dot(fill: Int, size: Int, stroke: Int = Color.WHITE) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(3), stroke)
        setSize(dp(size), dp(size))
    }

    private fun marker(position: GeoPoint, fill: Int, size: Int, onClick: (() -> Unit)? = null) = Marker(map).apply {
        this.position = position
        icon = dot(fill, size)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> onClick?.invoke(); true }
    }

    private fun line(color: Int) = Polyline(map).apply {
        setInfoWindow(null)
        outlinePaint.apply {
            this.color = color
            strokeWidth = dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
    }

    /** Adds route overlays below the markers and the copyright line. */
    private fun addBelowMarkers(overlay: org.osmdroid.views.overlay.Overlay) {
        val index = map.overlays.indexOfFirst { it is Marker || it is CopyrightOverlay }
        if (index < 0) map.overlays.add(overlay) else map.overlays.add(index, overlay)
    }

    // ------------------------------------------------------------------
    // Saved trip
    // ------------------------------------------------------------------

    private fun drawTrip() {
        recenter.visibility = View.GONE
        val t = SpeedTracker
        val record = trip
        title.text = if (record != null) "${t.formatDistance(record.distanceM)} ${t.distanceUnit} · ${t.formatDuration(record.elapsedMs)}" else "Trip"
        detail.text = if (record != null) {
            "avg ${t.formatSpeed(record.averageMps)} · max ${t.formatSpeed(record.maxSpeedMps)} ${t.speedUnit}"
        } else ""
        map.overlays.add(CopyrightOverlay(this))
        if (route.isEmpty()) {
            detail.text = "No route was recorded for this trip. Routes are saved for trips recorded from now on."
            map.controller.setZoom(4.0)
            return
        }

        val maxSpeed = maxOf(route.maxOf { it.speedMps }, record?.maxSpeedMps ?: 0f)
        val limits = bandLimits(maxSpeed)
        renderLegend(limits)

        // One polyline per run of points in the same speed band; neighbours share a point so the line is continuous
        var current: MutableList<GeoPoint> = mutableListOf()
        var currentBand = -1
        var currentSegment = route.first().segment
        fun flush() {
            if (current.size >= 2) addBelowMarkers(line(BAND_COLORS[currentBand.coerceAtLeast(0)]).apply { setPoints(current) })
        }
        route.forEach { p ->
            val geo = GeoPoint(p.lat, p.lon)
            val band = band(p.speedMps, limits)
            if (p.segment != currentSegment) {
                flush()
                current = mutableListOf()
                currentSegment = p.segment
            } else if (band != currentBand && current.isNotEmpty()) {
                flush()
                current = mutableListOf(current.last())
            }
            current += geo
            currentBand = band
        }
        flush()

        map.overlays.add(map.overlays.size - 1, marker(GeoPoint(route.first().lat, route.first().lon), Color.parseColor("#22C55E"), 18) { showPoint(route.first(), "Start") })
        map.overlays.add(map.overlays.size - 1, marker(GeoPoint(route.last().lat, route.last().lon), Color.parseColor("#EF4444"), 18) { showPoint(route.last(), "End") })

        val points = route.map { GeoPoint(it.lat, it.lon) }
        map.addOnFirstLayoutListener { _, _, _, _, _ -> fitTo(points) }
    }

    private fun fitTo(points: List<GeoPoint>) {
        if (points.size == 1 || points.distinctBy { it.latitude to it.longitude }.size == 1) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(points.first())
            return
        }
        val box = BoundingBox.fromGeoPointsSafe(points)
        map.zoomToBoundingBox(box.increaseByScale(1.35f), false)
    }

    private fun bandLimits(maxSpeed: Float): FloatArray {
        val top = maxSpeed.coerceAtLeast(3f)
        return floatArrayOf(top * 0.25f, top * 0.5f, top * 0.75f)
    }

    private fun band(speed: Float, limits: FloatArray) = when {
        speed < limits[0] -> 0
        speed < limits[1] -> 1
        speed < limits[2] -> 2
        else -> 3
    }

    private fun renderLegend(limits: FloatArray) {
        val t = SpeedTracker
        legend.removeAllViews()
        legend.visibility = View.VISIBLE
        val labels = listOf(
            "< ${t.formatSpeed(limits[0])}",
            "${t.formatSpeed(limits[0])}–${t.formatSpeed(limits[1])}",
            "${t.formatSpeed(limits[1])}–${t.formatSpeed(limits[2])}",
            "> ${t.formatSpeed(limits[2])} ${t.speedUnit}",
        )
        labels.forEachIndexed { i, label ->
            legend.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(BAND_COLORS[i])
                }
            }, LinearLayout.LayoutParams(dp(14), dp(4)).apply { if (i > 0) marginStart = dp(10) })
            legend.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
                text = label
                textSize = 10f
                setTextColor(color(R.color.slate_400))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
        }
    }

    /** Tap on the map: the closest recorded point within reach of the finger. */
    private fun showNearest(tap: GeoPoint) {
        if (route.isEmpty()) return
        val projection = map.projection
        val tapPx = projection.toPixels(tap, null)
        var best: RoutePoint? = null
        var bestDistance = Double.MAX_VALUE
        val px = Point()
        route.forEach { p ->
            projection.toPixels(GeoPoint(p.lat, p.lon), px)
            val d = hypot((px.x - tapPx.x).toDouble(), (px.y - tapPx.y).toDouble())
            if (d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        val point = best
        if (point == null || bestDistance > dp(36)) {
            pointInfo.visibility = View.GONE
            selectedMarker?.let { map.overlays.remove(it) }
            selectedMarker = null
            map.invalidate()
            return
        }
        showPoint(point, null)
    }

    private fun showPoint(point: RoutePoint, label: String?) {
        val t = SpeedTracker
        val time = if (point.time > 0) DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(point.time)) else ""
        pointInfo.text = listOfNotNull(label, "${t.formatSpeed(point.speedMps)} ${t.speedUnit}", time.ifEmpty { null }).joinToString(" · ")
        pointInfo.visibility = View.VISIBLE
        selectedMarker?.let { map.overlays.remove(it) }
        selectedMarker = marker(GeoPoint(point.lat, point.lon), Color.parseColor("#22D3EE"), 14).also {
            map.overlays.add(map.overlays.size - 1, it)
        }
        map.invalidate()
    }

    // ------------------------------------------------------------------
    // Live
    // ------------------------------------------------------------------

    private fun setupLive() {
        map.overlays.add(CopyrightOverlay(this))
        positionMarker = marker(GeoPoint(0.0, 0.0), Color.parseColor("#38BDF8"), 20).apply { isEnabled = false }
        map.overlays.add(map.overlays.size - 1, positionMarker)
        map.controller.setZoom(4.0)
        recenter.alpha = 0.5f
    }

    private fun setFollow(on: Boolean) {
        if (!isLive || follow == on) return
        follow = on
        recenter.alpha = if (on) 0.5f else 1f
    }

    private fun renderLive() {
        if (!::map.isInitialized || !isLive) return
        val t = SpeedTracker
        val current = TripRoute.current(this)
        if (TripRoute.version != drawnVersion) {
            drawnVersion = TripRoute.version
            syncLiveLines(current)
        }

        val position = t.position
        val marker = positionMarker
        if (position != null && marker != null && t.hasFix) {
            val geo = GeoPoint(position.latitude, position.longitude)
            marker.position = geo
            marker.isEnabled = true
            if (follow) {
                if (!centered) {
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(geo)
                    centered = true
                } else {
                    map.controller.animateTo(geo)
                }
            }
        } else if (!centered && current.isNotEmpty()) {
            val last = current.last()
            map.controller.setZoom(16.0)
            map.controller.setCenter(GeoPoint(last.lat, last.lon))
            centered = true
        }

        title.text = when (t.tripState) {
            TripState.RUNNING -> "● Recording · ${t.formatSpeed(t.speedMps)} ${t.speedUnit}"
            TripState.PAUSED -> "❚❚ Trip paused"
            TripState.IDLE -> if (current.isNotEmpty()) "Last trip" else "No trip running"
        }
        title.setTextColor(color(when (t.tripState) {
            TripState.RUNNING -> R.color.red_400
            TripState.PAUSED -> R.color.amber_400
            TripState.IDLE -> android.R.color.white
        }))
        detail.text = when {
            !hasLocationPermission() -> "Location permission is needed. Open the Speedometer to allow it."
            t.tripState == TripState.IDLE && current.isEmpty() ->
                "Start a trip on the Speedometer to record the route. It keeps recording with the screen off."
            !t.hasFix && t.tripState != TripState.IDLE -> "Waiting for GPS… · ${t.formatDistance(t.distanceM)} ${t.distanceUnit}"
            else -> "${t.formatDistance(t.distanceM)} ${t.distanceUnit} · ${t.formatDuration(t.elapsedMs)} · " +
                "max ${t.formatSpeed(t.maxSpeedMps)} ${t.speedUnit}"
        }
        map.invalidate()
    }

    /** Appends new points to the live lines; a pause starts a new line. */
    private fun syncLiveLines(current: List<RoutePoint>) {
        if (current.size < drawnCount) {
            liveLines.forEach { map.overlays.remove(it) }
            liveLines.clear()
            drawnCount = 0
        }
        var lastSegment = if (drawnCount > 0) current[drawnCount - 1].segment else -1
        for (i in drawnCount until current.size) {
            val p = current[i]
            if (liveLines.isEmpty() || p.segment != lastSegment) {
                liveLines += line(accent).also { addBelowMarkers(it) }
                lastSegment = p.segment
            }
            liveLines.last().addPoint(GeoPoint(p.lat, p.lon))
        }
        drawnCount = current.size
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening || !hasLocationPermission()) return
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            listening = true
        }
    }

    private fun stopListening() {
        if (!listening) return
        locationManager.removeUpdates(locationListener)
        listening = false
    }

    // ------------------------------------------------------------------
    // Dark map and GPX
    // ------------------------------------------------------------------

    private fun isDark() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DARK, true)

    private fun applyDark() {
        map.overlayManager.tilesOverlay.setColorFilter(if (isDark()) TilesOverlay.INVERT_COLORS else null)
        map.setBackgroundColor(if (isDark()) Color.parseColor("#0B0E14") else Color.parseColor("#E5E7EB"))
        map.invalidate()
    }

    private fun toggleDark() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DARK, !isDark()).apply()
        applyDark()
        renderDarkButton(darkButton)
    }

    private fun renderDarkButton(button: ImageButton) {
        button.setImageResource(if (isDark()) R.drawable.ic_sun else R.drawable.ic_moon)
        button.contentDescription = if (isDark()) "Light map" else "Dark map"
    }

    private fun shareGpx() {
        val points = if (isLive) TripRoute.current(this) else route
        if (points.isEmpty()) {
            Toast.makeText(this, "No route to share yet", Toast.LENGTH_SHORT).show()
            return
        }
        val start = points.first().time.takeIf { it > 0 } ?: trip?.startedAt ?: System.currentTimeMillis()
        val name = "Trip " + android.text.format.DateFormat.format("yyyy-MM-dd HH-mm", start)
        runCatching { TripRoute.exportGpx(this, name, points) }
            .onSuccess { uri ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Share route (GPX)"))
            }
            .onFailure { Toast.makeText(this, "Couldn't export the route", Toast.LENGTH_SHORT).show() }
    }
}
