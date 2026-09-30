package com.truckcontroller.pro.transfer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * File Transfer: the phone serves a web page on the local network (same Wi-Fi, either device's
 * hotspot, or USB tethering). Browsers on PCs connect only after the phone approves them, then
 * send files and text to each other and to the phone through it, and may browse the phone's
 * storage when their access level allows. Nothing is installed on the PCs.
 * Settings and live state live here; [FileTransferService] runs [Hub] and [TransferServer].
 */
object FileTransfer {

    const val DEFAULT_PORT = 8080
    private const val HISTORY = 20
    private const val LOG_SIZE = 60

    private const val PREFS = "file_transfer"
    private const val KEY_ALLOW_PIN = "allow_pin"
    private const val KEY_DEFAULT_ACCESS = "default_access"
    private const val KEY_SHARED_FOLDERS = "shared_folders"
    private const val KEY_AUTO_STOP = "auto_stop_minutes"
    internal const val KEY_REMEMBERED = "remembered_devices"

    enum class ServerState { OFF, STARTING, RUNNING }

    /** What a connected browser may do. Every browser can send and receive; the rest is opt-in. */
    enum class Access(val title: String, val description: String) {
        SEND("Send & receive only", "Can send files and text to other devices and receive them. Can't see the phone's files."),
        FOLDERS("Shared folders", "Can also open the folders you share in Security › Shared folders."),
        FULL("All files", "Can browse, download, change and delete everything in the phone's storage."),
    }

    /** A folder browsers with [Access.FOLDERS] may open; [writable] lets them upload, rename and delete in it. */
    data class SharedFolder(val path: String, val writable: Boolean) {
        val name: String get() = File(path).name.ifEmpty { path }
    }

    @Volatile var state = ServerState.OFF
        internal set
    @Volatile var port = 0
        internal set
    /** New random PIN every time the server starts. */
    @Volatile var pin = ""
        internal set
    /** Why the server could not start (port in use, no storage access). */
    @Volatile var error: String? = null
        internal set
    /** Devices, approvals, shares and texts while the server runs. */
    @Volatile var hub: Hub? = null
        internal set

    val isRunning get() = state == ServerState.RUNNING

    /** Where files sent to the phone are saved. */
    val receivedDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PhoneDeck")

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    fun allowPin(context: Context) = prefs(context).getBoolean(KEY_ALLOW_PIN, true)
    fun setAllowPin(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ALLOW_PIN, value).apply()

    /** Access given to a device approved from the notification or with the PIN. */
    fun defaultAccess(context: Context): Access =
        runCatching { Access.valueOf(prefs(context).getString(KEY_DEFAULT_ACCESS, null)!!) }.getOrDefault(Access.SEND)
    fun setDefaultAccess(context: Context, access: Access) =
        prefs(context).edit().putString(KEY_DEFAULT_ACCESS, access.name).apply()

    /** Stop the server after this many minutes without any connected browser; 0 = never. */
    fun autoStopMinutes(context: Context) = prefs(context).getInt(KEY_AUTO_STOP, 30)
    fun setAutoStopMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt(KEY_AUTO_STOP, minutes).apply()

    fun sharedFolders(context: Context): List<SharedFolder> {
        val json = prefs(context).getString(KEY_SHARED_FOLDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                SharedFolder(o.getString("path"), o.optBoolean("writable", false))
            }
        }.getOrDefault(emptyList())
    }

    fun setSharedFolders(context: Context, folders: List<SharedFolder>) {
        val array = JSONArray()
        folders.distinctBy { it.path }.forEach { array.put(JSONObject().put("path", it.path).put("writable", it.writable)) }
        prefs(context).edit().putString(KEY_SHARED_FOLDERS, array.toString()).apply()
    }

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context, Intent(context, FileTransferService::class.java).setAction(FileTransferService.ACTION_START)
        )
    }

    fun stop(context: Context) {
        context.startService(Intent(context, FileTransferService::class.java).setAction(FileTransferService.ACTION_STOP))
    }

    internal fun newPin(): String = String.format("%06d", SecureRandom().nextInt(1_000_000))

    // ------------------------------------------------------------------
    // Storage access
    // ------------------------------------------------------------------

    /** Android 11+: "All files access"; Android 9–10: the storage permissions. */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    // ------------------------------------------------------------------
    // Addresses the PC can reach
    // ------------------------------------------------------------------

    data class Address(val label: String, val ip: String) {
        fun url(port: Int) = "http://$ip:$port"
    }

    /** Local IPv4 addresses by network: Wi-Fi, hotspot, USB tethering, Ethernet. Mobile data is skipped. */
    fun addresses(): List<Address> {
        val result = mutableListOf<Address>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in interfaces) {
            if (runCatching { !nif.isUp || nif.isLoopback }.getOrDefault(true)) continue
            val name = nif.name.lowercase()
            val label = when {
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-") ||
                    name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("seth") -> continue
                name.startsWith("p2p") -> "Wi-Fi Direct"
                name == "wlan0" -> "Wi-Fi"
                name.contains("ap") || name.startsWith("wlan") || name.startsWith("swlan") -> "Hotspot"
                name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "USB tethering"
                name.startsWith("eth") -> "Ethernet"
                else -> "Network"
            }
            nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .forEach { result += Address(label, it.hostAddress ?: return@forEach) }
        }
        val order = listOf("Wi-Fi", "Hotspot", "USB tethering", "Ethernet", "Wi-Fi Direct", "Network")
        return result.distinctBy { it.ip }.sortedBy { order.indexOf(it.label) }
    }

    // ------------------------------------------------------------------
    // Live activity shown on the phone
    // ------------------------------------------------------------------

    enum class Direction { TO_PHONE, FROM_PHONE, RELAY }

    class Transfer(val name: String, val direction: Direction, val total: Long, val peer: String) {
        val id = ids.incrementAndGet()
        val startedAt = SystemClock.elapsedRealtime()
        @Volatile var done = 0L
        @Volatile var finished = false
        @Volatile var failed = false
        @Volatile var endedAt = 0L

        /** Bytes per second since the start. */
        val speed: Long
            get() {
                val ms = (if (finished) endedAt else SystemClock.elapsedRealtime()) - startedAt
                return if (ms <= 0) 0 else done * 1000 / ms
            }
    }

    private val ids = AtomicLong()
    val transfers = CopyOnWriteArrayList<Transfer>()

    /** Bytes sent to and received from browsers since the server started. */
    val bytesSent = AtomicLong()
    val bytesReceived = AtomicLong()

    /** Last request from any browser (for auto-stop). */
    @Volatile var lastActivity = 0L
        private set

    internal fun begin(name: String, direction: Direction, total: Long, peer: String): Transfer =
        Transfer(name, direction, total, peer).also { transfers.add(0, it) }

    internal fun end(transfer: Transfer, ok: Boolean) {
        transfer.failed = !ok
        transfer.endedAt = SystemClock.elapsedRealtime()
        transfer.finished = true
        // Keep the running ones and the latest finished
        val old = transfers.filter { it.finished }.drop(HISTORY)
        transfers.removeAll(old.toSet())
    }

    internal fun seen() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    val activeTransfers get() = transfers.filter { !it.finished }

    // ------------------------------------------------------------------
    // Security log
    // ------------------------------------------------------------------

    class LogEntry(val time: Long, val text: String, val warning: Boolean)

    val log = CopyOnWriteArrayList<LogEntry>()

    internal fun log(text: String, warning: Boolean = false) {
        log.add(0, LogEntry(System.currentTimeMillis(), text, warning))
        while (log.size > LOG_SIZE) log.removeAt(log.size - 1)
    }

    internal fun reset() {
        transfers.clear()
        bytesSent.set(0)
        bytesReceived.set(0)
        lastActivity = SystemClock.elapsedRealtime()
    }
}
