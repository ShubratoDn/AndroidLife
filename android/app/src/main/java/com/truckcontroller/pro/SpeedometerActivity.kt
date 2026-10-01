package com.truckcontroller.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.truckcontroller.pro.speed.SpeedTracker
import com.truckcontroller.pro.speed.TripHistory
import com.truckcontroller.pro.speed.TripRecord
import com.truckcontroller.pro.speed.TripRoute
import java.text.DateFormat
import java.util.Date
import com.truckcontroller.pro.speed.SpeedTracker.TripState
import com.truckcontroller.pro.speed.SpeedometerView
import com.truckcontroller.pro.speed.TripService
import com.truckcontroller.pro.ui.ActionTile
import kotlin.math.roundToInt

/**
 * GPS speedometer with trip recording: current speed, max speed, average speed, trip time,
 * distance, moving time and moving average. Trips keep recording in the background.
 */
class SpeedometerActivity : BaseActivity() {

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var pendingTripStart = false

    private lateinit var gauge: SpeedometerView
    private lateinit var tvGps: TextView
    private lateinit var tvUnit: TextView
    private lateinit var tvTripState: TextView
    private lateinit var btnPrimary: ActionTile
    private lateinit var btnSecondary: ActionTile
    private val statValues = HashMap<String, TextView>()
    private val statUnits = HashMap<String, TextView>()

    private val locationListener = LocationListener { location -> SpeedTracker.onLocation(this, location) }
    private val trackerListener: () -> Unit = { render() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                startListening()
                if (pendingTripStart) startTrip()
            } else {
                pendingTripStart = false
                Toast.makeText(this, "Location permission is needed to measure speed", Toast.LENGTH_LONG).show()
            }
            render()
        }

    private val ticker = object : Runnable {
        override fun run() {
            SpeedTracker.tick()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        haptics = HapticFeedbackHelper(this)
        locationManager = getSystemService(LocationManager::class.java)
        SpeedTracker.load(this)
        setContentView(buildScreen())
        if (!hasLocationPermission()) requestLocation()
    }

    override fun onStart() {
        super.onStart()
        SpeedTracker.addListener(trackerListener)
        startListening()
        handler.post(ticker)
        render()
    }

    override fun onStop() {
        super.onStop()
        SpeedTracker.removeListener(trackerListener)
        stopListening()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private val gpsEnabled get() = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening || !hasLocationPermission()) return
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        listening = true
    }

    private fun stopListening() {
        if (!listening) return
        locationManager.removeUpdates(locationListener)
        listening = false
    }

    // ------------------------------------------------------------------
    // Trip controls
    // ------------------------------------------------------------------

    private fun startTrip() {
        pendingTripStart = false
        if (!hasLocationPermission()) {
            pendingTripStart = true
            requestLocation()
            return
        }
        if (!gpsEnabled) {
            Toast.makeText(this, "Turn on Location (GPS) to record a trip", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        TripService.send(this, TripService.ACTION_START)
    }

    private fun onPrimary() {
        haptics.performButtonClickHaptic()
        when (SpeedTracker.tripState) {
            TripState.IDLE -> startTrip()
            TripState.RUNNING -> TripService.send(this, TripService.ACTION_PAUSE)
            TripState.PAUSED -> TripService.send(this, TripService.ACTION_RESUME)
        }
    }

    private fun onSecondary() {
        haptics.performButtonClickHaptic()
        if (SpeedTracker.tripState == TripState.IDLE) {
            SpeedTracker.clearTrip(this)
        } else {
            TripService.send(this, TripService.ACTION_END)
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.cockpit_bg))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dp(46)))
        root.addView(View(this).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(MATCH, dp(1)))

        gauge = SpeedometerView(this)
        tvTripState = mono(11f, R.color.slate_500).apply {
            letterSpacing = 0.2f
            setTypeface(typeface, Typeface.BOLD)
        }
        val stats = buildStats()
        val controls = buildControls()
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val content = if (portrait) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(14))
                addView(gauge, LinearLayout.LayoutParams(MATCH, 0, 1.25f))
                addView(tvTripState, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
                addView(stats, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
                addView(controls, LinearLayout.LayoutParams(MATCH, dp(72)).apply { topMargin = dp(12) })
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(8), dp(14), dp(12))
                addView(gauge, LinearLayout.LayoutParams(0, MATCH, 1f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tvTripState, LinearLayout.LayoutParams(WRAP, WRAP))
                    addView(stats, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
                    addView(controls, LinearLayout.LayoutParams(MATCH, dp(64)).apply { topMargin = dp(10) })
                }, LinearLayout.LayoutParams(0, MATCH, 1.25f).apply { marginStart = dp(16) })
            }
        }
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(color(R.color.header_bg))

        addView(ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(R.drawable.ic_home)
            setColorFilter(color(R.color.slate_300))
            contentDescription = "Home"
            setOnClickListener { haptics.performButtonClickHaptic(); finish() }
        }, LinearLayout.LayoutParams(dp(38), dp(34)))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_gauge)
            setColorFilter(color(R.color.amber_400))
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
        addView(TextView(context).apply {
            text = "Speedometer"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            // Portrait header is narrow: the gauge icon identifies the screen, keep room for the buttons
            visibility = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) View.GONE else View.VISIBLE
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        tvGps = mono(11f, R.color.slate_400).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onGpsPillClick() }
        }
        addView(tvGps, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        tvUnit = mono(12f, R.color.slate_300).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_header_icon)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                haptics.performButtonClickHaptic()
                SpeedTracker.setUseMph(this@SpeedometerActivity, !SpeedTracker.useMph)
            }
        }
        addView(ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(R.drawable.ic_route)
            setColorFilter(color(R.color.slate_300))
            contentDescription = "Route map"
            setOnClickListener {
                haptics.performButtonClickHaptic()
                startActivity(RouteMapActivity.live(this@SpeedometerActivity))
            }
        }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginEnd = dp(6) })
        addView(ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(R.drawable.ic_history)
            setColorFilter(color(R.color.slate_300))
            contentDescription = "Trip history"
            setOnClickListener {
                haptics.performButtonClickHaptic()
                showHistory()
            }
        }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginEnd = dp(6) })
        addView(tvUnit, LinearLayout.LayoutParams(WRAP, dp(34)))
        val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
        addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(6) })
        bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
    }

    private fun onGpsPillClick() {
        when {
            !hasLocationPermission() -> requestLocation()
            !gpsEnabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    /** Six stat cards in a 2 x 3 grid. */
    private fun buildStats(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        listOf(
            listOf("MAX SPEED" to R.color.red_400, "AVG SPEED" to R.color.emerald_400),
            listOf("TRIP TIME" to R.color.amber_400, "DISTANCE" to R.color.cyan_400),
            listOf("MOVING TIME" to R.color.slate_400, "MOVING AVG" to R.color.slate_400),
        ).forEachIndexed { rowIndex, row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, (label, accent) ->
                    addView(statCard(label, accent), LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        if (i > 0) marginStart = dp(8)
                    })
                }
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { if (rowIndex > 0) topMargin = dp(8) })
        }
    }

    private fun statCard(label: String, @ColorRes accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_panel)
        setPadding(dp(14), dp(4), dp(10), dp(4))
        addView(mono(10f, accent).apply {
            text = label
            letterSpacing = 0.15f
            setTypeface(typeface, Typeface.BOLD)
        })
        val valueRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val value = mono(24f, android.R.color.white).apply {
            setTypeface(typeface, Typeface.BOLD)
            text = "0"
        }
        val unit = mono(11f, R.color.slate_500).apply { setPadding(dp(5), 0, 0, dp(3)) }
        valueRow.addView(value)
        valueRow.addView(unit)
        addView(valueRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        statValues[label] = value
        statUnits[label] = unit
    }

    private fun buildControls(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        btnPrimary = ActionTile(context).apply {
            primary = true
            setOnClickListener { onPrimary() }
        }
        btnSecondary = ActionTile(context).apply { setOnClickListener { onSecondary() } }
        addView(btnPrimary, LinearLayout.LayoutParams(0, MATCH, 1.4f))
        addView(btnSecondary, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(10) })
    }

    // ------------------------------------------------------------------
    // Trip history
    // ------------------------------------------------------------------

    private fun showHistory() {
        val t = SpeedTracker
        val trips = TripHistory.all(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null

        if (trips.isEmpty()) {
            list.addView(mono(13f, R.color.slate_400).apply {
                text = "No saved trips yet.\nStart a trip and tap END TRIP to save it here."
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            val totalDistance = trips.sumOf { it.distanceM }
            val totalTime = trips.sumOf { it.elapsedMs }
            val topSpeed = trips.maxOf { it.maxSpeedMps }
            list.addView(mono(12f, R.color.amber_400).apply {
                setTypeface(typeface, Typeface.BOLD)
                text = "${trips.size} trips · ${t.formatDistance(totalDistance)} ${t.distanceUnit} · " +
                    "${t.formatDuration(totalTime)} · top ${t.formatSpeed(topSpeed)} ${t.speedUnit}"
                setPadding(0, 0, 0, dp(8))
            })
            list.addView(mono(10f, R.color.slate_500).apply {
                text = "Tap a trip to see its route · long-press to delete"
                setPadding(0, 0, 0, dp(6))
            })
            trips.forEach { trip ->
                list.addView(tripRow(trip, onClick = {
                    dialog?.dismiss()
                    startActivity(RouteMapActivity.trip(this, trip.id))
                }) { dialog?.dismiss(); confirmDelete(trip) })
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Trip history")
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setPositiveButton("Close", null)
        if (trips.isNotEmpty()) {
            builder.setNeutralButton("Clear all") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete all trips?")
                    .setMessage("This removes all ${trips.size} saved trips.")
                    .setPositiveButton("Delete") { _, _ -> TripHistory.clear(this) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        dialog = builder.show()
    }

    private fun tripRow(trip: TripRecord, onClick: () -> Unit, onLongPress: () -> Unit): View = LinearLayout(this).apply {
        val t = SpeedTracker
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_step_button)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isLongClickable = true
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        setOnLongClickListener { onLongPress(); true }

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        addView(TextView(context).apply {
            text = dateFormat.format(Date(trip.startedAt))
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(mono(12f, R.color.cyan_400).apply {
            text = "${t.formatDistance(trip.distanceM)} ${t.distanceUnit}  ·  ${t.formatDuration(trip.elapsedMs)}"
            setPadding(0, dp(4), 0, 0)
        })
        addView(mono(11f, R.color.slate_400).apply {
            text = "max ${t.formatSpeed(trip.maxSpeedMps)} · avg ${t.formatSpeed(trip.averageMps)} · " +
                "moving avg ${t.formatSpeed(trip.movingAverageMps)} ${t.speedUnit}\n" +
                "moving time ${t.formatDuration(trip.movingMs)}"
            setPadding(0, dp(2), 0, 0)
        })
        if (TripRoute.hasRoute(context, trip.id)) addView(mono(11f, R.color.emerald_400).apply {
            text = "▸ Route on map"
            setPadding(0, dp(4), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
    }

    private fun confirmDelete(trip: TripRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this trip?")
            .setMessage(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.startedAt)))
            .setPositiveButton("Delete") { _, _ ->
                TripHistory.delete(this, trip.id)
                showHistory()
            }
            .setNegativeButton("Cancel") { _, _ -> showHistory() }
            .show()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        if (!::gauge.isInitialized) return
        val t = SpeedTracker
        val fix = t.hasFix

        gauge.unit = t.speedUnit
        gauge.hasFix = fix
        gauge.speed = if (fix) t.toDisplaySpeed(t.speedMps) else 0f
        gauge.maxMark = if (t.hasTripData) t.toDisplaySpeed(t.maxSpeedMps) else 0f
        gauge.statusText = when {
            !hasLocationPermission() -> "LOCATION PERMISSION NEEDED"
            !gpsEnabled -> "LOCATION IS OFF"
            fix -> if (t.accuracyM > 0) "GPS ±${t.accuracyM.roundToInt()} m" else "GPS"
            else -> "WAITING FOR GPS…"
        }

        // GPS pill
        val (pillText, pillColor) = when {
            !hasLocationPermission() -> "NO PERMISSION" to R.color.red_400
            !gpsEnabled -> "GPS OFF" to R.color.red_400
            fix -> "GPS ±${t.accuracyM.roundToInt().coerceAtLeast(1)} m" to R.color.emerald_400
            else -> "SEARCHING…" to R.color.amber_400
        }
        tvGps.text = pillText
        val c = color(pillColor)
        tvGps.setTextColor(c)
        tvGps.background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(Color.argb(38, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(dp(1), Color.argb(90, Color.red(c), Color.green(c), Color.blue(c)))
        }
        tvUnit.text = if (t.useMph) "MPH" else "KM/H"

        // Trip stats
        setStat("MAX SPEED", t.formatSpeed(t.maxSpeedMps), t.speedUnit)
        setStat("AVG SPEED", t.formatSpeed(t.averageMps), t.speedUnit)
        setStat("TRIP TIME", t.formatDuration(t.elapsedMs), "")
        setStat("DISTANCE", t.formatDistance(t.distanceM), t.distanceUnit)
        setStat("MOVING TIME", t.formatDuration(t.movingMs), "")
        setStat("MOVING AVG", t.formatSpeed(t.movingAverageMps), t.speedUnit)

        tvTripState.text = when (t.tripState) {
            TripState.RUNNING -> "● TRIP RECORDING"
            TripState.PAUSED -> "❚❚ TRIP PAUSED"
            TripState.IDLE -> if (t.hasTripData) "LAST TRIP · SAVED TO HISTORY" else "NO TRIP YET — START ONE BELOW"
        }
        tvTripState.setTextColor(color(when (t.tripState) {
            TripState.RUNNING -> R.color.red_400
            TripState.PAUSED -> R.color.amber_400
            TripState.IDLE -> R.color.slate_500
        }))

        // Controls
        when (t.tripState) {
            TripState.IDLE -> {
                btnPrimary.configure(if (t.hasTripData) "NEW TRIP" else "START TRIP", R.drawable.ic_play, R.color.emerald_400)
                btnSecondary.configure("CLEAR", R.drawable.ic_rotate_ccw, R.color.slate_400)
                btnSecondary.visibility = if (t.hasTripData) View.VISIBLE else View.GONE
            }
            TripState.RUNNING -> {
                btnPrimary.configure("PAUSE", R.drawable.ic_pause, R.color.amber_400)
                btnSecondary.configure("END TRIP", R.drawable.ic_stop, R.color.red_400)
                btnSecondary.visibility = View.VISIBLE
            }
            TripState.PAUSED -> {
                btnPrimary.configure("RESUME", R.drawable.ic_play, R.color.emerald_400)
                btnSecondary.configure("END TRIP", R.drawable.ic_stop, R.color.red_400)
                btnSecondary.visibility = View.VISIBLE
            }
        }
    }

    private fun ActionTile.configure(label: String, icon: Int, @ColorRes accent: Int) {
        if (title != label) {
            title = label
            setIcon(icon)
        }
        accentColor = color(accent)
    }

    private fun setStat(key: String, value: String, unit: String) {
        statValues[key]?.text = value
        statUnits[key]?.text = unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun mono(size: Float, @ColorRes colorRes: Int) = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        textSize = size
        setTextColor(color(colorRes))
    }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
