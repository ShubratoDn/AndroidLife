package com.truckcontroller.pro.dimmer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.truckcontroller.pro.HomeActivity
import com.truckcontroller.pro.R

/**
 * Foreground service that keeps a translucent black (or warm amber) overlay above every app.
 * The overlay ignores touches, and its window alpha stays at or below 80 % so Android keeps
 * delivering taps to the apps underneath. The ongoing notification adjusts the dim level.
 */
class NightScreenService : Service() {

    companion object {
        const val ACTION_START = "com.truckcontroller.pro.night.START"
        const val ACTION_STOP = "com.truckcontroller.pro.night.STOP"
        const val ACTION_DIMMER = "com.truckcontroller.pro.night.DIMMER"     // darker
        const val ACTION_BRIGHTER = "com.truckcontroller.pro.night.BRIGHTER"
        const val ACTION_TOGGLE_WARM = "com.truckcontroller.pro.night.WARM"
        const val ACTION_REFRESH = "com.truckcontroller.pro.night.REFRESH"

        private const val CHANNEL_ID = "night_screen"
        private const val NOTIFICATION_ID = 42
        private val WARM_COLOR = Color.rgb(70, 32, 0)
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DIMMER -> NightScreen.setLevel(this, NightScreen.level(this) + NightScreen.STEP)
            ACTION_BRIGHTER -> NightScreen.setLevel(this, NightScreen.level(this) - NightScreen.STEP)
            ACTION_TOGGLE_WARM -> NightScreen.setWarm(this, !NightScreen.isWarm(this))
        }
        if (!NightScreen.canDrawOverlays(this)) {
            // Permission was revoked: nothing can be drawn
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        NightScreen.isRunning = true
        showOrUpdateOverlay()
        refreshTile()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        NightScreen.isRunning = false
        refreshTile()
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    private fun showOrUpdateOverlay() {
        val alpha = NightScreen.level(this) / 100f
        val color = if (NightScreen.isWarm(this)) WARM_COLOR else Color.BLACK
        val existing = overlay
        if (existing != null) {
            existing.setBackgroundColor(color)
            val params = existing.layoutParams as WindowManager.LayoutParams
            params.alpha = alpha
            windowManager.updateViewLayout(existing, params)
            return
        }
        val view = View(this).apply { setBackgroundColor(color) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Window alpha (not the view colour) decides whether touches pass through on Android 12+
            this.alpha = alpha
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager.addView(view, params)
        overlay = view
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Night Screen", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Screen dimmer controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, NightScreenService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val level = NightScreen.level(this)
        val warm = NightScreen.isWarm(this)
        val status = "Dimmed $level %" + if (warm) " · Warm" else ""

        val views = RemoteViews(packageName, R.layout.notification_night_screen).apply {
            setTextViewText(R.id.tvNightLevel, status)
            setProgressBar(R.id.pbNightLevel, NightScreen.MAX_LEVEL, level, false)
            setTextViewText(R.id.btnNightWarm, if (warm) "Cool" else "Warm")
            setBoolean(R.id.btnNightBrighter, "setEnabled", level > NightScreen.MIN_LEVEL)
            setBoolean(R.id.btnNightDimmer, "setEnabled", level < NightScreen.MAX_LEVEL)
            setOnClickPendingIntent(R.id.btnNightBrighter, action(ACTION_BRIGHTER, 1))
            setOnClickPendingIntent(R.id.btnNightDimmer, action(ACTION_DIMMER, 2))
            setOnClickPendingIntent(R.id.btnNightWarm, action(ACTION_TOGGLE_WARM, 3))
            setOnClickPendingIntent(R.id.btnNightOff, action(ACTION_STOP, 4))
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Night Screen")
            .setContentText(status)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, NightScreenTileService::class.java))
    }
}
