package com.truckcontroller.pro.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.truckcontroller.pro.ClipboardSendActivity
import com.truckcontroller.pro.FileTransferActivity
import com.truckcontroller.pro.R
import com.truckcontroller.pro.formatBytes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs the [Hub] and [TransferServer] while the screen is off or another app is open. Keeps the
 * CPU and Wi-Fi awake so transfers keep full speed, shows the address and live progress in an
 * ongoing notification, and asks the user about new devices, incoming files and texts with
 * heads-up notifications. Stops by itself after a while with no connected browser.
 */
class FileTransferService : Service() {

    companion object {
        const val ACTION_START = "com.truckcontroller.pro.transfer.START"
        const val ACTION_STOP = "com.truckcontroller.pro.transfer.STOP"
        private const val ACTION_ALLOW = "com.truckcontroller.pro.transfer.ALLOW"
        private const val ACTION_DENY = "com.truckcontroller.pro.transfer.DENY"
        private const val ACTION_ACCEPT = "com.truckcontroller.pro.transfer.ACCEPT"
        private const val ACTION_DECLINE = "com.truckcontroller.pro.transfer.DECLINE"
        private const val ACTION_COPY = "com.truckcontroller.pro.transfer.COPY"
        const val ACTION_CAMERA_START = "com.truckcontroller.pro.transfer.CAMERA_START"
        const val ACTION_CAMERA_STOP = "com.truckcontroller.pro.transfer.CAMERA_STOP"
        const val ACTION_SCREEN_START = "com.truckcontroller.pro.transfer.SCREEN_START"
        const val ACTION_SCREEN_STOP = "com.truckcontroller.pro.transfer.SCREEN_STOP"
        private const val ACTION_LIVE_STOP = "com.truckcontroller.pro.transfer.LIVE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_ID = "id"

        private const val CHANNEL_ID = "file_transfer"
        private const val ALERT_CHANNEL_ID = "file_transfer_alerts"
        private const val NOTIFICATION_ID = 44
        private const val TICK_MS = 1_000L
    }

    private var server: TransferServer? = null
    private var hub: Hub? = null
    private var cameraShare: CameraShare? = null
    private var screenShare: ScreenShare? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""
    /** Last time a browser was connected (for auto-stop). */
    private var lastOnline = 0L

    private val notificationIds = ConcurrentHashMap<String, Int>()
    private val receivedNotified = HashSet<String>()
    private val nextId = AtomicInteger(1000)
    private fun alertId(key: String) = notificationIds.getOrPut(key) { nextId.incrementAndGet() }

    private val ticker = object : Runnable {
        override fun run() {
            hub?.cleanup()
            updateNotification()
            checkAutoStop()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "File Transfer", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Address and progress while PCs can reach this phone"
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "File Transfer requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Devices asking to connect, incoming files and texts"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ALLOW, ACTION_DENY, ACTION_ACCEPT, ACTION_DECLINE, ACTION_COPY -> {
                // A button on an old notification must never start the server again
                if (server == null) stopSelf() else handleAction(intent)
                return START_NOT_STICKY
            }
            ACTION_CAMERA_START, ACTION_CAMERA_STOP, ACTION_SCREEN_START, ACTION_SCREEN_STOP, ACTION_LIVE_STOP -> {
                if (server == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                when (intent.action) {
                    ACTION_CAMERA_START -> startCamera()
                    ACTION_CAMERA_STOP -> stopCamera()
                    ACTION_SCREEN_START -> startScreen(intent)
                    ACTION_SCREEN_STOP -> stopScreen()
                    ACTION_LIVE_STOP -> {
                        stopCamera()
                        stopScreen()
                    }
                }
                return START_NOT_STICKY
            }
        }
        updateForeground()
        if (server == null) startServer()
        return START_NOT_STICKY
    }

    /** Foreground types follow what runs: network always, plus camera / screen capture while shared. */
    private fun updateForeground() {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (screenShare != null || pendingScreen) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (cameraShare != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
    }

    // ------------------------------------------------------------------
    // Live view: camera and screen
    // ------------------------------------------------------------------

    private fun startCamera() {
        if (cameraShare != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val share = CameraShare(this, LiveShare.quality(this)) { error ->
            handler.post {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                stopCamera()
            }
        }
        cameraShare = share
        // The camera type must be in place before the camera opens
        runCatching { updateForeground() }.onFailure {
            cameraShare = null
            Toast.makeText(this, "Android didn't allow the camera in the background", Toast.LENGTH_LONG).show()
            return
        }
        share.start()
        FileTransfer.log("Camera sharing started")
    }

    private fun stopCamera() {
        val share = cameraShare ?: return
        cameraShare = null
        share.stop()
        FileTransfer.log("Camera sharing stopped")
        if (server != null) updateForeground()
    }

    private fun startScreen(intent: Intent) {
        if (screenShare != null) return
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java) ?: return
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: the service must already run as screen capture before getting the projection
        pendingScreen = true
        val projection = runCatching {
            updateForeground()
            manager.getMediaProjection(code, data)
        }.getOrNull()
        pendingScreen = false
        if (projection == null) {
            Toast.makeText(this, "Screen sharing wasn't allowed", Toast.LENGTH_LONG).show()
            updateForeground()
            return
        }
        val share = ScreenShare(this, projection, LiveShare.quality(this)) { handler.post { stopScreen() } }
        screenShare = share
        runCatching { share.start() }.onFailure {
            screenShare = null
            share.stop()
            Toast.makeText(this, "Couldn't capture the screen: ${it.message}", Toast.LENGTH_LONG).show()
            updateForeground()
            return
        }
        FileTransfer.log("Screen sharing started")
    }

    /** True while switching the service to screen-capture type, before the projection exists. */
    private var pendingScreen = false

    private fun stopScreen() {
        val share = screenShare ?: return
        screenShare = null
        share.stop()
        FileTransfer.log("Screen sharing stopped")
        if (server != null) updateForeground()
    }

    private fun onLiveControl(source: String, action: String) {
        val camera = cameraShare ?: return
        if (source != "camera") return
        when (action) {
            "lens" -> camera.switchLens()
            "torch" -> camera.toggleTorch()
            "rotate" -> camera.rotate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        cameraShare?.stop()
        cameraShare = null
        screenShare?.stop()
        screenShare = null
        LiveShare.control = null
        LiveShare.onChanged = null
        val s = server
        val h = hub
        server = null
        hub = null
        FileTransfer.hub = null
        thread {
            h?.shutdown()
            s?.stop()
        }
        releaseLocks()
        val manager = getSystemService(NotificationManager::class.java)
        notificationIds.values.forEach { manager.cancel(it) }
        FileTransfer.state = FileTransfer.ServerState.OFF
        FileTransfer.log("File Transfer stopped")
    }

    private fun startServer() {
        if (!FileTransfer.hasStorageAccess(this)) {
            FileTransfer.error = "Allow access to files first"
            stopSelf()
            return
        }
        val h = Hub(applicationContext).also { it.listener = hubListener }
        LiveShare.onChanged = {
            h.broadcast(LiveShare.statusJson())
            handler.post { updateNotification() }
        }
        LiveShare.control = { source, action -> handler.post { onLiveControl(source, action) } }
        val s = TransferServer(applicationContext, h)
        hub = h
        server = s
        FileTransfer.hub = h
        FileTransfer.reset()
        FileTransfer.error = null
        FileTransfer.pin = FileTransfer.newPin()
        FileTransfer.state = FileTransfer.ServerState.STARTING
        lastOnline = SystemClock.elapsedRealtime()
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
                    FileTransfer.log("File Transfer started on port $it")
                    acquireLocks()
                    handler.post(ticker)
                }.onFailure {
                    FileTransfer.error = "Could not open a network port: ${it.message}"
                    stopSelf()
                }
            }
        }
    }

    private fun checkAutoStop() {
        val h = hub ?: return
        val now = SystemClock.elapsedRealtime()
        if (h.onlineDevices().isNotEmpty() || FileTransfer.activeTransfers.isNotEmpty() || h.pendingRequests().isNotEmpty()) {
            lastOnline = now
            return
        }
        val minutes = FileTransfer.autoStopMinutes(this)
        if (minutes > 0 && now - lastOnline > minutes * 60_000L) {
            FileTransfer.log("Stopped automatically after $minutes min with no device connected")
            stopSelf()
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
    // Requests, incoming files and texts
    // ------------------------------------------------------------------

    private val hubListener = object : Hub.Listener {
        override fun onRequest(request: Hub.AccessRequest) {
            handler.post { notifyRequest(request) }
        }

        override fun onRequestClosed(request: Hub.AccessRequest) {
            handler.post { cancelAlert("req:${request.id}") }
        }

        override fun onShareChanged(share: Hub.Share) {
            handler.post { notifyShare(share) }
        }

        override fun onText(text: Hub.TextMessage) {
            handler.post { notifyText(text) }
        }

        override fun onClip(clip: Hub.ClipItem) {
            handler.post {
                // Writing the clipboard is allowed from the background (reading isn't)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhoneDeck", clip.text))
                // Android 13+ shows its own "copied" confirmation
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this@FileTransferService, "Copied from ${clip.fromName}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleAction(intent: Intent) {
        val h = hub ?: return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_ALLOW -> h.approve(id, FileTransfer.defaultAccess(this), remember = false)
            ACTION_DENY -> h.deny(id)
            ACTION_ACCEPT -> h.share(id)?.let { h.accept(it, Hub.PHONE_ID) }
            ACTION_DECLINE -> h.share(id)?.let { h.decline(it, Hub.PHONE_ID) }
            ACTION_COPY -> h.texts().firstOrNull { it.id == id }?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Text", it.text))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                cancelAlert("text:$id")
            }
        }
    }

    private fun actionIntent(action: String, id: String, request: Int) = PendingIntent.getService(
        this, request, Intent(this, FileTransferService::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openApp(request: Int = 0) = PendingIntent.getActivity(
        this, request, Intent(this, FileTransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun alert(key: String, build: NotificationCompat.Builder.() -> Unit) {
        val id = alertId(key)
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfer)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(id))
            .apply(build)
        runCatching { getSystemService(NotificationManager::class.java).notify(id, builder.build()) }
    }

    private fun cancelAlert(key: String) {
        notificationIds.remove(key)?.let { getSystemService(NotificationManager::class.java).cancel(it) }
    }

    private fun notifyRequest(request: Hub.AccessRequest) {
        if (request.status != Hub.RequestStatus.PENDING) return
        val key = "req:${request.id}"
        val id = alertId(key)
        val access = FileTransfer.defaultAccess(this)
        alert(key) {
            setContentTitle("${request.name} wants to connect")
            setContentText("Code ${request.code} · ${request.ip}")
            setStyle(NotificationCompat.BigTextStyle().bigText(
                "Code ${request.code} · ${request.ip}\nAllow only if the PC shows the same code. " +
                    "Allowed from here: ${access.title}. Open the app to choose other access."
            ))
            setCategory(NotificationCompat.CATEGORY_CALL)
            setOngoing(true)
            setTimeoutAfter(120_000)
            addAction(R.drawable.ic_close, "Deny", actionIntent(ACTION_DENY, request.id, id * 10 + 1))
            addAction(R.drawable.ic_shield, "Allow", actionIntent(ACTION_ALLOW, request.id, id * 10 + 2))
        }
    }

    private fun notifyShare(share: Hub.Share) {
        val phone = share.recipients[Hub.PHONE_ID] ?: return
        val key = "share:${share.id}"
        val id = alertId(key)
        when (phone.state) {
            Hub.RecipientState.PENDING -> alert(key) {
                setContentTitle("${share.fromName} wants to send you ${share.title}")
                setContentText("${formatBytes(share.total)} · saved to Download/PhoneDeck")
                setCategory(NotificationCompat.CATEGORY_MESSAGE)
                setOnlyAlertOnce(true)
                addAction(R.drawable.ic_close, "Decline", actionIntent(ACTION_DECLINE, share.id, id * 10 + 1))
                addAction(R.drawable.ic_file, "Accept", actionIntent(ACTION_ACCEPT, share.id, id * 10 + 2))
            }
            Hub.RecipientState.DONE -> if (receivedNotified.add(share.id)) alert(key) {
                setContentTitle("Received ${share.title} from ${share.fromName}")
                setContentText("${formatBytes(share.total)} · in Download/PhoneDeck")
                setOnlyAlertOnce(false)
                setAutoCancel(true)
            }
            Hub.RecipientState.ACCEPTED, Hub.RecipientState.RECEIVING -> cancelAlert(key)
            else -> cancelAlert(key)
        }
    }

    private fun notifyText(text: Hub.TextMessage) {
        val key = "text:${text.id}"
        val id = alertId(key)
        alert(key) {
            setContentTitle("Text from ${text.fromName}")
            setContentText(text.text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text.text))
            setCategory(NotificationCompat.CATEGORY_MESSAGE)
            setAutoCancel(true)
            addAction(R.drawable.ic_copy, "Copy", actionIntent(ACTION_COPY, text.id, id * 10 + 1))
        }
    }

    // ------------------------------------------------------------------
    // Ongoing notification
    // ------------------------------------------------------------------

    private fun updateNotification() {
        val notification = buildNotification()
        // Only re-post when something changed: avoids flicker and rate limits
        val text = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() +
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() +
            notification.extras.getInt(Notification.EXTRA_PROGRESS) +
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FileTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .addAction(R.drawable.ic_copy, "Send clipboard", PendingIntent.getActivity(
                this, 3, Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

        if (FileTransfer.state != FileTransfer.ServerState.RUNNING) {
            return builder.setContentTitle("File Transfer").setContentText("Starting…").build()
        }
        val address = FileTransfer.addresses().firstOrNull()
        val active = FileTransfer.activeTransfers
        val online = hub?.onlineDevices().orEmpty()
        if (active.isNotEmpty()) {
            val total = active.sumOf { it.total }.coerceAtLeast(1)
            val done = active.sumOf { it.done }
            val speed = active.sumOf { it.speed }
            val name = if (active.size == 1) active.first().name else "${active.size} transfers"
            builder.setContentTitle("Transferring · ${(done * 100 / total).toInt()} %")
                .setContentText("$name · ${formatBytes(done)} of ${formatBytes(total)} · ${formatBytes(speed)}/s")
                .setProgress(1000, (done * 1000 / total).toInt(), false)
        } else if (address == null) {
            builder.setContentTitle("File Transfer on · no network")
                .setContentText("Connect to Wi-Fi or turn on the hotspot")
        } else {
            val devices = when (online.size) {
                0 -> "No device connected"
                1 -> "${online[0].name} connected"
                else -> "${online.size} devices connected"
            }
            builder.setContentTitle("File Transfer on · $devices")
                .setContentText("Open ${address.url(FileTransfer.port)} on your PC")
        }
        if (LiveShare.sharing) {
            val what = listOfNotNull("camera".takeIf { LiveShare.camera.on }, "screen".takeIf { LiveShare.screen.on }).joinToString(" and ")
            val watching = LiveShare.camera.viewerCount + LiveShare.screen.viewerCount
            builder.setSubText("Sharing $what · $watching watching")
            builder.addAction(R.drawable.ic_eye, "Stop sharing", PendingIntent.getService(
                this, 2, Intent(this, FileTransferService::class.java).setAction(ACTION_LIVE_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }
        return builder.build()
    }
}
