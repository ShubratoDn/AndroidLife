package com.truckcontroller.pro.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.truckcontroller.pro.FileTransferActivity
import com.truckcontroller.pro.R
import com.truckcontroller.pro.formatBytes
import kotlin.concurrent.thread

/**
 * Runs the [TransferServer] while the screen is off or another app is open. Keeps the CPU and
 * Wi-Fi awake so transfers keep full speed, and shows the address, PIN and live progress in an
 * ongoing notification with a Stop button.
 */
class FileTransferService : Service() {

    companion object {
        const val ACTION_START = "com.truckcontroller.pro.transfer.START"
        const val ACTION_STOP = "com.truckcontroller.pro.transfer.STOP"

        private const val CHANNEL_ID = "file_transfer"
        private const val NOTIFICATION_ID = 44
        private const val TICK_MS = 1_000L
    }

    private var server: TransferServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""

    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "File Transfer", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Address, PIN and progress while the PC can reach this phone"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
        if (server == null) startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        server?.let { s -> thread { s.stop() } }
        server = null
        releaseLocks()
        FileTransfer.state = FileTransfer.ServerState.OFF
    }

    private fun startServer() {
        if (!FileTransfer.hasStorageAccess(this)) {
            FileTransfer.error = "Allow access to files first"
            stopSelf()
            return
        }
        val s = TransferServer(applicationContext)
        server = s
        FileTransfer.reset()
        FileTransfer.error = null
        FileTransfer.pin = FileTransfer.newPin()
        FileTransfer.state = FileTransfer.ServerState.STARTING
        // Binding the socket counts as network work: keep it off the main thread
        thread(name = "transfer-start") {
            val port = runCatching { s.start(FileTransfer.DEFAULT_PORT) }
            handler.post {
                if (server !== s) {
                    s.stop()
                    return@post
                }
                port.onSuccess {
                    FileTransfer.port = it
                    FileTransfer.state = FileTransfer.ServerState.RUNNING
                    acquireLocks()
                    handler.post(ticker)
                }.onFailure {
                    FileTransfer.error = "Could not open a network port: ${it.message}"
                    stopSelf()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneDeck:FileTransfer")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneDeck:FileTransfer")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun updateNotification() {
        val notification = buildNotification()
        // Only re-post when something changed: avoids flicker and rate limits
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() +
            notification.extras.getInt(Notification.EXTRA_PROGRESS)
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, FileTransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FileTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, "Stop", stop)

        if (FileTransfer.state != FileTransfer.ServerState.RUNNING) {
            return builder.setContentTitle("File Transfer").setContentText("Starting…").build()
        }
        val address = FileTransfer.addresses().firstOrNull()
        val pin = if (FileTransfer.requirePin(this)) " · PIN ${FileTransfer.pin}" else ""
        val active = FileTransfer.activeTransfers
        if (active.isNotEmpty()) {
            val total = active.sumOf { it.total }.coerceAtLeast(1)
            val done = active.sumOf { it.done }
            val speed = active.sumOf { it.speed }
            val uploads = active.count { it.upload }
            val title = when {
                uploads == active.size -> "Receiving from PC"
                uploads == 0 -> "Sending to PC"
                else -> "Transferring"
            }
            val name = if (active.size == 1) active.first().name else "${active.size} files"
            builder.setContentTitle("$title · ${(done * 100 / total).toInt()} %")
                .setContentText("$name · ${formatBytes(done)} of ${formatBytes(total)} · ${formatBytes(speed)}/s")
                .setProgress(1000, (done * 1000 / total).toInt(), false)
        } else if (address == null) {
            builder.setContentTitle("File Transfer on · no network")
                .setContentText("Connect to Wi-Fi or turn on the hotspot")
        } else {
            builder.setContentTitle("File Transfer on")
                .setContentText("Open ${address.url(FileTransfer.port)} on your PC$pin")
        }
        return builder.build()
    }
}
