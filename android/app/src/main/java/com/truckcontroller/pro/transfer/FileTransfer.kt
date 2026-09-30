package com.truckcontroller.pro.transfer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * File Transfer: the phone serves a web page on the local network (same Wi-Fi, either device's
 * hotspot, or USB tethering). Any browser on a PC opens it to browse, download and upload files;
 * nothing is installed on the PC. State lives here, [FileTransferService] runs [TransferServer].
 */
object FileTransfer {

    const val DEFAULT_PORT = 8080
    /** A browser that polled within this time counts as connected. */
    private const val CLIENT_TIMEOUT_MS = 45_000L
    private const val HISTORY = 20

    private const val PREFS = "file_transfer"
    private const val KEY_REQUIRE_PIN = "require_pin"
    private const val KEY_READ_ONLY = "read_only"

    enum class ServerState { OFF, STARTING, RUNNING }

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

    val isRunning get() = state == ServerState.RUNNING

    // ------------------------------------------------------------------
    // Options (read live by the server on every request)
    // ------------------------------------------------------------------

    fun requirePin(context: Context) = prefs(context).getBoolean(KEY_REQUIRE_PIN, true)
    fun setRequirePin(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_REQUIRE_PIN, value).apply()

    fun readOnly(context: Context) = prefs(context).getBoolean(KEY_READ_ONLY, false)
    fun setReadOnly(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_READ_ONLY, value).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    class Transfer(val name: String, val upload: Boolean, val total: Long, val client: String) {
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
    private val clients = ConcurrentHashMap<String, Long>()

    /** Bytes sent to and received from browsers since the server started. */
    val bytesSent = AtomicLong()
    val bytesReceived = AtomicLong()

    internal fun begin(name: String, upload: Boolean, total: Long, client: String): Transfer =
        Transfer(name, upload, total, client).also { transfers.add(0, it) }

    internal fun end(transfer: Transfer, ok: Boolean) {
        transfer.failed = !ok
        transfer.endedAt = SystemClock.elapsedRealtime()
        transfer.finished = true
        // Keep the running ones and the latest finished
        val old = transfers.filter { it.finished }.drop(HISTORY)
        transfers.removeAll(old.toSet())
    }

    internal fun seen(client: String) {
        clients[client] = SystemClock.elapsedRealtime()
    }

    /** Browsers active in the last [CLIENT_TIMEOUT_MS]. */
    fun connectedClients(): List<String> {
        val now = SystemClock.elapsedRealtime()
        return clients.filterValues { now - it < CLIENT_TIMEOUT_MS }.keys.sorted()
    }

    val activeTransfers get() = transfers.filter { !it.finished }

    internal fun reset() {
        transfers.clear()
        clients.clear()
        bytesSent.set(0)
        bytesReceived.set(0)
    }
}
