package com.truckcontroller.pro.speed

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R
import com.truckcontroller.pro.SpeedometerActivity

/**
 * Keeps recording a trip while the screen is off or another app is open. Shows live speed and
 * trip distance in an ongoing notification with Pause / Resume / End buttons.
 */
class TripService : Service() {

    companion object {
        const val ACTION_START = "com.truckcontroller.pro.trip.START"
        const val ACTION_PAUSE = "com.truckcontroller.pro.trip.PAUSE"
        const val ACTION_RESUME = "com.truckcontroller.pro.trip.RESUME"
        const val ACTION_END = "com.truckcontroller.pro.trip.END"

        private const val CHANNEL_ID = "trip"
        private const val NOTIFICATION_ID = 43
        private const val NOTIFY_INTERVAL_MS = 2_000L

        fun send(context: Context, action: String) {
            val intent = Intent(context, TripService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }

    private lateinit var locationManager: LocationManager
    private var listening = false
    private var lastNotifyAt = 0L

    private val locationListener = LocationListener { location: Location ->
        SpeedTracker.onLocation(this, location)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
            lastNotifyAt = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        SpeedTracker.load(this)
        val channel = NotificationChannel(CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live speed and trip progress"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> SpeedTracker.startTrip(this)
            ACTION_PAUSE -> SpeedTracker.pauseTrip(this)
            ACTION_RESUME -> SpeedTracker.resumeTrip(this)
            ACTION_END -> {
                SpeedTracker.endTrip(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (SpeedTracker.tripState == SpeedTracker.TripState.IDLE) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        // GPS only while recording; a paused trip does not drain the battery
        if (SpeedTracker.tripState == SpeedTracker.TripState.RUNNING) startListening() else stopListening()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        listening = true
    }

    private fun stopListening() {
        if (!listening) return
        locationManager.removeUpdates(locationListener)
        listening = false
    }

    private fun action(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, TripService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val t = SpeedTracker
        val running = t.tripState == SpeedTracker.TripState.RUNNING
        val title = if (running) {
            "Trip · ${t.formatSpeed(t.speedMps)} ${t.speedUnit}"
        } else {
            "Trip paused"
        }
        val text = "${t.formatDistance(t.distanceM)} ${t.distanceUnit} · ${t.formatDuration(t.elapsedMs)} · " +
            "avg ${t.formatSpeed(t.averageMps)} · max ${t.formatSpeed(t.maxSpeedMps)} ${t.speedUnit}"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SpeedometerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gauge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (running) R.drawable.ic_pause else R.drawable.ic_play,
                if (running) "Pause" else "Resume",
                action(if (running) ACTION_PAUSE else ACTION_RESUME, 1)
            )
            .addAction(R.drawable.ic_stop, "End trip", action(ACTION_END, 2))
            .build()
    }
}
