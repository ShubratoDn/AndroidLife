package com.truckcontroller.pro.transfer

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import com.truckcontroller.pro.transfer.FileTransfer.Access
import com.truckcontroller.pro.transfer.FileTransfer.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Everything between the connected devices while the server runs:
 * - devices: browsers the phone approved (or that entered the PIN), with an [Access] level each;
 *   remembered devices keep their session across restarts,
 * - access requests waiting for the phone's Allow / Deny,
 * - shares: files sent from one device to others, relayed live through the phone or stored on it,
 * - text messages,
 * - a live event stream per browser tab.
 */
class Hub(private val context: Context) {

    companion object {
        const val PHONE_ID = "phone"
        private const val REQUEST_TTL_MS = 2 * 60_000L
        private const val REQUESTS_PER_WINDOW = 8
        private const val REQUEST_WINDOW_MS = 10 * 60_000L
        private const val PIN_ATTEMPTS = 5
        private const val PIN_LOCKOUT_MS = 60_000L
        private const val ONLINE_GRACE_MS = 15_000L
        private const val SHARE_TTL_MS = 2 * 60 * 60_000L
        private const val FINISHED_KEEP_MS = 30 * 60_000L
        private const val PIPE_WAIT_MS = 10 * 60_000L
        private const val MAX_NAME = 40
        private const val MAX_TEXT = 10_000
        private const val MAX_FILES = 10_000
        private const val MAX_SHARES = 200
        private const val TEXT_HISTORY = 30
        private const val BUFFER = 256 * 1024
        internal const val KICKED = "{\"type\":\"kicked\"}"

        private val random = SecureRandom()

        fun randomHex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun sha256(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun cleanDeviceName(name: String?, fallback: String) =
            name.orEmpty().replace(Regex("[\\x00-\\x1F<>]"), "").trim().take(MAX_NAME).ifEmpty { fallback }

        private val PLATFORMS = setOf("windows", "mac", "linux", "chromeos", "android", "iphone")
        fun cleanPlatform(platform: String?) = platform.orEmpty().lowercase().takeIf { it in PLATFORMS } ?: "other"

        private fun elapsed() = SystemClock.elapsedRealtime()
    }

    /** Phone-side reactions (notifications, dialogs). Called on server threads. */
    interface Listener {
        fun onRequest(request: AccessRequest) {}
        fun onRequestClosed(request: AccessRequest) {}
        /** A share the phone sends or receives changed state. */
        fun onShareChanged(share: Share) {}
        /** Text sent to the phone. */
        fun onText(text: TextMessage) {}
    }

    @Volatile var listener: Listener? = null

    val phoneName: String = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private val sharesRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "shares")

    init {
        loadRemembered()
        // Leftovers from a previous run that was killed
        sharesRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Devices
    // ------------------------------------------------------------------

    class Device internal constructor(
        val id: String,
        name: String,
        platform: String,
        access: Access,
        remembered: Boolean,
        internal val tokenHash: String,
    ) {
        @Volatile var name = name
            internal set
        @Volatile var platform = platform
            internal set
        @Volatile var access = access
            internal set
        @Volatile var remembered = remembered
            internal set
        @Volatile var ip = ""
            internal set
        /** elapsedRealtime of the last request in this run; 0 = not seen since the server started. */
        @Volatile internal var lastSeen = 0L
        /** Wall clock of the last request, for display. */
        @Volatile var lastSeenAt = 0L
            internal set
        internal val streams = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()

        val online get() = streams.isNotEmpty() || (lastSeen > 0 && elapsed() - lastSeen < ONLINE_GRACE_MS)
    }

    private val devices = ConcurrentHashMap<String, Device>()
    private val byToken = ConcurrentHashMap<String, Device>()
    private var onlineKey = ""

    fun devices(): List<Device> =
        devices.values.sortedWith(compareByDescending<Device> { it.online }.thenBy { it.name.lowercase() })

    fun device(id: String?) = id?.let { devices[it] }

    fun onlineDevices() = devices().filter { it.online }

    internal fun deviceForToken(token: String?): Device? =
        token?.takeIf { it.length in 32..128 }?.let { byToken[sha256(it)] }

    internal fun isCurrent(device: Device) = devices[device.id] === device

    internal fun touch(device: Device, ip: String) {
        val wasOnline = device.online
        device.lastSeen = elapsed()
        device.lastSeenAt = System.currentTimeMillis()
        device.ip = ip
        if (!wasOnline) broadcastDevices()
    }

    private fun addDevice(name: String, platform: String, access: Access, remember: Boolean, ip: String): Pair<Device, String> {
        val token = randomHex(32)
        val device = Device(randomHex(6), name, platform, access, remember, sha256(token))
        device.ip = ip
        device.lastSeen = elapsed()
        device.lastSeenAt = System.currentTimeMillis()
        devices[device.id] = device
        byToken[device.tokenHash] = device
        if (remember) saveRemembered()
        broadcastDevices()
        return device to token
    }

    fun setAccess(id: String, access: Access) {
        val device = devices[id] ?: return
        if (device.access == access) return
        device.access = access
        if (device.remembered) saveRemembered()
        FileTransfer.log("${device.name}: access changed to ${access.title}", warning = access == Access.FULL)
        emit(device, meJson(device))
    }

    fun setRemembered(id: String, remember: Boolean) {
        val device = devices[id] ?: return
        device.remembered = remember
        saveRemembered()
    }

    internal fun rename(device: Device, name: String?) {
        val clean = cleanDeviceName(name, device.name)
        if (clean == device.name) return
        device.name = clean
        if (device.remembered) saveRemembered()
        emit(device, meJson(device))
        broadcastDevices()
    }

    /** Ends the device's session and forgets it; the browser has to ask again. */
    fun remove(id: String, byPhone: Boolean = true) {
        val device = devices.remove(id) ?: return
        byToken.remove(device.tokenHash)
        if (device.remembered) saveRemembered()
        device.streams.forEach { it.offer(KICKED) }
        shares.values.filter { !it.finished }.forEach { share ->
            if (share.fromId == id) cancelShare(share) else if (share.recipients.containsKey(id)) decline(share, id)
        }
        FileTransfer.log(if (byPhone) "${device.name} was disconnected from the phone" else "${device.name} signed out")
        broadcastDevices()
    }

    fun forgetAll() {
        devices.values.filter { it.remembered }.forEach { remove(it.id) }
        FileTransfer.prefs(context).edit().remove(FileTransfer.KEY_REMEMBERED).apply()
    }

    private fun saveRemembered() {
        val array = JSONArray()
        devices.values.filter { it.remembered }.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("platform", it.platform)
                .put("access", it.access.name).put("token", it.tokenHash).put("seen", it.lastSeenAt))
        }
        FileTransfer.prefs(context).edit().putString(FileTransfer.KEY_REMEMBERED, array.toString()).apply()
    }

    private fun loadRemembered() {
        val json = FileTransfer.prefs(context).getString(FileTransfer.KEY_REMEMBERED, null) ?: return
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val access = runCatching { Access.valueOf(o.getString("access")) }.getOrDefault(Access.SEND)
                val device = Device(o.getString("id"), o.getString("name"), o.optString("platform", "other"), access, true, o.getString("token"))
                device.lastSeenAt = o.optLong("seen")
                devices[device.id] = device
                byToken[device.tokenHash] = device
            }
        }
    }

    // ------------------------------------------------------------------
    // Access requests
    // ------------------------------------------------------------------

    enum class RequestStatus { PENDING, APPROVED, DENIED, EXPIRED }

    class AccessRequest internal constructor(
        /** Secret: only the browser that asked knows it, so nobody else can collect the approval. */
        val id: String,
        /** Shown on both screens so the person approving can match them. */
        val code: String,
        val name: String,
        val platform: String,
        val ip: String,
    ) {
        internal val created = elapsed()
        @Volatile var status = RequestStatus.PENDING
            internal set
        @Volatile internal var token: String? = null
        @Volatile var remembered = false
            internal set
        internal val decided = CountDownLatch(1)
    }

    class LimitException(message: String) : Exception(message)

    private val requests = ConcurrentHashMap<String, AccessRequest>()
    private val requestTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private class PinAttempts(var count: Int = 0, var lockedUntil: Long = 0)
    private val pinAttempts = ConcurrentHashMap<String, PinAttempts>()

    fun pendingRequests() = requests.values.filter { it.status == RequestStatus.PENDING }.sortedBy { it.created }

    fun request(id: String?) = id?.let { requests[it] }

    internal fun requestAccess(name: String?, platform: String?, ip: String): AccessRequest {
        val now = elapsed()
        val times = requestTimes.getOrPut(ip) { mutableListOf() }
        synchronized(times) {
            times.removeAll { now - it > REQUEST_WINDOW_MS }
            if (times.size >= REQUESTS_PER_WINDOW) {
                FileTransfer.log("Too many connection requests from $ip; blocked for a while", warning = true)
                throw LimitException("Too many requests from this device. Try again in a few minutes.")
            }
            times += now
        }
        // One open request per address
        requests.values.filter { it.ip == ip && it.status == RequestStatus.PENDING }.forEach { close(it, RequestStatus.EXPIRED) }
        val request = AccessRequest(
            randomHex(16), String.format("%04d", random.nextInt(10_000)),
            cleanDeviceName(name, "Unknown device"), cleanPlatform(platform), ip,
        )
        requests[request.id] = request
        FileTransfer.log("${request.name} ($ip) asked to connect")
        listener?.onRequest(request)
        return request
    }

    fun approve(id: String, access: Access, remember: Boolean): Boolean {
        val request = requests[id] ?: return false
        synchronized(request) {
            if (request.status != RequestStatus.PENDING) return false
            val (device, token) = addDevice(request.name, request.platform, access, remember, request.ip)
            request.token = token
            request.remembered = remember
            FileTransfer.log("${device.name} (${request.ip}) allowed · ${access.title}", warning = access == Access.FULL)
            close(request, RequestStatus.APPROVED)
        }
        return true
    }

    fun deny(id: String) {
        val request = requests[id] ?: return
        synchronized(request) {
            if (request.status != RequestStatus.PENDING) return
            FileTransfer.log("${request.name} (${request.ip}) was denied", warning = true)
            close(request, RequestStatus.DENIED)
        }
    }

    private fun close(request: AccessRequest, status: RequestStatus) {
        request.status = status
        request.decided.countDown()
        listener?.onRequestClosed(request)
    }

    /** Browser side: waits for the phone's answer. Only the address that asked may collect it. */
    internal fun awaitRequest(id: String?, ip: String, timeoutMs: Long): AccessRequest? {
        val request = request(id)?.takeIf { it.ip == ip } ?: return null
        request.decided.await(timeoutMs, TimeUnit.MILLISECONDS)
        return request
    }

    /** The session token is handed out once. */
    internal fun collectToken(request: AccessRequest): String? = synchronized(request) {
        request.token.also { request.token = null }
    }

    /** PIN sign-in: a new device with the default access, or null for a wrong PIN. */
    internal fun loginWithPin(pin: String, name: String?, platform: String?, ip: String): Pair<Device, String>? {
        if (!FileTransfer.allowPin(context)) throw LimitException("PIN sign-in is turned off on the phone. Ask for access instead.")
        val now = elapsed()
        val tries = pinAttempts.getOrPut(ip) { PinAttempts() }
        synchronized(tries) {
            if (now < tries.lockedUntil) {
                throw LimitException("Too many wrong PINs. Try again in ${(tries.lockedUntil - now) / 1000 + 1} s")
            }
            if (pin != FileTransfer.pin) {
                tries.count++
                if (tries.count >= PIN_ATTEMPTS) {
                    tries.count = 0
                    tries.lockedUntil = now + PIN_LOCKOUT_MS
                    FileTransfer.log("Wrong PIN $PIN_ATTEMPTS times from $ip; locked for a minute", warning = true)
                }
                return null
            }
            tries.count = 0
        }
        val access = FileTransfer.defaultAccess(context)
        val result = addDevice(cleanDeviceName(name, "Unknown device"), cleanPlatform(platform), access, false, ip)
        FileTransfer.log("${result.first.name} ($ip) signed in with the PIN · ${access.title}", warning = access == Access.FULL)
        return result
    }

    // ------------------------------------------------------------------
    // Live events (one queue per open browser tab)
    // ------------------------------------------------------------------

    internal fun openStream(device: Device): LinkedBlockingQueue<String> {
        val queue = LinkedBlockingQueue<String>(2000)
        snapshot(device).forEach { queue.offer(it.toString()) }
        val wasOnline = device.online
        device.streams += queue
        touch(device, device.ip)
        if (!wasOnline) broadcastDevices()
        return queue
    }

    internal fun closeStream(device: Device, queue: LinkedBlockingQueue<String>) {
        device.streams -= queue
        device.lastSeen = elapsed()
    }

    private fun emit(device: Device, json: JSONObject) {
        val text = json.toString()
        device.streams.forEach { it.offer(text) }
    }

    private fun emitTo(ids: Collection<String>, json: JSONObject) {
        val text = json.toString()
        ids.mapNotNull { devices[it] }.forEach { d -> d.streams.forEach { it.offer(text) } }
    }

    private fun broadcastDevices() {
        onlineKey = onlineDevices().joinToString { it.id + it.name }
        val json = devicesJson()
        devices.values.forEach { emit(it, json) }
    }

    private fun devicesJson(): JSONObject {
        val list = JSONArray()
        list.put(JSONObject().put("id", PHONE_ID).put("name", phoneName).put("platform", "phone"))
        onlineDevices().forEach { list.put(JSONObject().put("id", it.id).put("name", it.name).put("platform", it.platform)) }
        return JSONObject().put("type", "devices").put("devices", list)
    }

    internal fun meJson(device: Device): JSONObject = JSONObject()
        .put("type", "me")
        .put("id", device.id)
        .put("name", device.name)
        .put("platform", device.platform)
        .put("access", device.access.name)
        .put("accessTitle", device.access.title)
        .put("remembered", device.remembered)

    private fun snapshot(device: Device): List<JSONObject> {
        val list = mutableListOf(meJson(device), devicesJson())
        shares.values.sortedBy { it.created }
            .filter { it.fromId == device.id || it.recipients.containsKey(device.id) }
            .forEach { list += shareJson(it) }
        texts.reversed().filter { t -> t.fromId == device.id || t.to.any { it.first == device.id } }
            .forEach { list += textJson(it) }
        return list
    }

    // ------------------------------------------------------------------
    // Shares
    // ------------------------------------------------------------------

    /**
     * RELAY: one browser to one browser, streamed through the phone without saving.
     * DIRECT: a browser to the phone, saved straight into Download/PhoneDeck.
     * STORED: a browser to several devices; uploaded once to the phone, then each downloads it.
     * SOURCE: the phone sends its own files.
     */
    enum class Mode { RELAY, DIRECT, STORED, SOURCE }
    enum class UploadState { WAITING, REQUESTED, UPLOADING, DONE, FAILED, CANCELLED }
    enum class RecipientState { PENDING, ACCEPTED, RECEIVING, DONE, DECLINED, FAILED, CANCELLED }

    private val terminal = setOf(RecipientState.DONE, RecipientState.DECLINED, RecipientState.FAILED, RecipientState.CANCELLED)

    class ShareFile internal constructor(val index: Int, val name: String, val size: Long, internal val uri: Uri? = null) {
        @Volatile internal var file: File? = null
        @Volatile internal var received = false
    }

    class Recipient internal constructor(val deviceId: String, val name: String) {
        @Volatile var state = RecipientState.PENDING
            internal set
        @Volatile var done = 0L
            internal set
        @Volatile var error: String? = null
            internal set
    }

    /** Connects one uploading request to the downloading request of a relay. */
    internal class Pipe {
        private val arrived = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        @Volatile private var input: InputStream? = null
        @Volatile var ok = false
            private set

        fun offer(stream: InputStream) {
            input = stream
            arrived.countDown()
        }

        fun awaitInput(stop: () -> Boolean): InputStream {
            val until = elapsed() + PIPE_WAIT_MS
            while (!arrived.await(500, TimeUnit.MILLISECONDS)) {
                if (stop() || elapsed() > until) throw IOException("The sender didn't start the upload")
            }
            return input!!
        }

        fun finish(success: Boolean) {
            ok = success
            finished.countDown()
        }

        /** Uploader side: blocks until the receiver has taken the whole file. */
        fun awaitFinished(stop: () -> Boolean): Boolean {
            while (!finished.await(500, TimeUnit.MILLISECONDS)) {
                if (stop()) return false
            }
            return ok
        }
    }

    class Share internal constructor(
        val id: String,
        val fromId: String,
        val fromName: String,
        val files: List<ShareFile>,
        val recipients: Map<String, Recipient>,
        val mode: Mode,
    ) {
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        internal val created = elapsed()
        val createdAt = System.currentTimeMillis()
        @Volatile var upload = if (mode == Mode.SOURCE) UploadState.DONE else UploadState.WAITING
            internal set
        @Volatile var uploaded = 0L
            internal set
        @Volatile var cancelled = false
            internal set
        @Volatile internal var finishedAt = 0L
        internal val pipes = ConcurrentHashMap<Int, Pipe>()
        @Volatile internal var lastEmit = 0L
        val finished get() = finishedAt > 0

        internal fun pipe(index: Int) = pipes.getOrPut(index) { Pipe() }

        val title: String
            get() = if (files.size == 1) files[0].name.substringAfterLast('/') else "${files.size} files"
    }

    private val shares = ConcurrentHashMap<String, Share>()

    fun shares(): List<Share> = shares.values.sortedByDescending { it.created }

    fun share(id: String?) = id?.let { shares[it] }

    private fun cacheDir(share: Share) = File(sharesRoot, share.id)

    private fun recipientsFor(fromId: String, to: List<String>): LinkedHashMap<String, Recipient> {
        val result = LinkedHashMap<String, Recipient>()
        to.distinct().filter { it != fromId }.forEach { id ->
            result[id] = if (id == PHONE_ID) {
                Recipient(PHONE_ID, phoneName)
            } else {
                val device = devices[id]?.takeIf { it.online } ?: throw IllegalArgumentException("A chosen device is no longer connected")
                Recipient(device.id, device.name)
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException("Choose who to send to")
        return result
    }

    /** A browser offers files; they are uploaded only after someone accepts. */
    internal fun createShare(from: Device, files: List<Pair<String, Long>>, to: List<String>): Share {
        if (files.isEmpty()) throw IllegalArgumentException("No files")
        if (files.size > MAX_FILES) throw IllegalArgumentException("Too many files at once (max $MAX_FILES)")
        if (shares.values.count { it.fromId == from.id && !it.finished } >= 20) {
            throw LimitException("Too many sends waiting. Finish or cancel some first.")
        }
        val shareFiles = files.mapIndexed { i, (name, size) ->
            val clean = Names.cleanRelative(name) ?: throw IllegalArgumentException("Invalid file name: $name")
            if (size < 0) throw IllegalArgumentException("Invalid file size")
            ShareFile(i, clean, size)
        }
        val recipients = recipientsFor(from.id, to)
        val mode = when {
            recipients.keys.singleOrNull() == PHONE_ID -> Mode.DIRECT
            recipients.size == 1 -> Mode.RELAY
            else -> Mode.STORED
        }
        return register(Share(randomHex(8), from.id, from.name, shareFiles, recipients, mode))
    }

    /** The phone sends files it picked (content URIs). */
    fun createPhoneShare(uris: List<Uri>, to: List<String>): Share {
        if (uris.isEmpty()) throw IllegalArgumentException("No files")
        val files = uris.mapIndexed { i, uri ->
            var name: String? = null
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            ShareFile(i, Names.clean(name) ?: "file-${i + 1}", size, uri)
        }
        // Several files with the same name would collide in the zip
        val seen = HashSet<String>()
        val unique = files.map { f ->
            var n = f.name
            var k = 1
            while (!seen.add(n)) n = "${f.name.substringBeforeLast('.')} (${k++})" +
                f.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            if (n == f.name) f else ShareFile(f.index, n, f.size, f.uri)
        }
        return register(Share(randomHex(8), PHONE_ID, phoneName, unique, recipientsFor(PHONE_ID, to), Mode.SOURCE))
    }

    private fun register(share: Share): Share {
        if (shares.size >= MAX_SHARES) cleanup(force = true)
        shares[share.id] = share
        val to = share.recipients.values.joinToString { it.name }
        FileTransfer.log("${share.fromName} → $to: ${share.title} (${formatSize(share.total)})")
        changed(share, force = true)
        return share
    }

    fun accept(share: Share, deviceId: String): Boolean {
        val r = share.recipients[deviceId] ?: return false
        synchronized(share) {
            if (r.state != RecipientState.PENDING || share.cancelled) return false
            r.state = RecipientState.ACCEPTED
            if (share.upload == UploadState.WAITING) share.upload = UploadState.REQUESTED
        }
        if (deviceId == PHONE_ID && share.mode == Mode.STORED && share.upload == UploadState.DONE) copyToPhone(share)
        changed(share, force = true)
        return true
    }

    /** A recipient says no (or stops receiving). */
    fun decline(share: Share, deviceId: String) {
        val r = share.recipients[deviceId] ?: return
        synchronized(share) {
            if (r.state in terminal) return
            r.state = if (r.state == RecipientState.PENDING) RecipientState.DECLINED else RecipientState.CANCELLED
        }
        // Relay and direct shares exist only for this one recipient
        if (share.mode == Mode.RELAY || share.mode == Mode.DIRECT) cancelShare(share) else checkFinished(share)
        changed(share, force = true)
    }

    fun cancelShare(share: Share) {
        synchronized(share) {
            if (share.finished && share.cancelled) return
            share.cancelled = true
            share.recipients.values.filter { it.state !in terminal }.forEach { it.state = RecipientState.CANCELLED }
            if (share.upload != UploadState.DONE) share.upload = UploadState.CANCELLED
        }
        share.pipes.values.forEach { it.finish(false) }
        checkFinished(share)
        changed(share, force = true)
    }

    private fun failShare(share: Share, reason: String) {
        synchronized(share) {
            share.cancelled = true
            if (share.upload != UploadState.DONE) share.upload = UploadState.FAILED
            share.recipients.values.filter { it.state !in terminal }.forEach {
                it.state = RecipientState.FAILED
                it.error = reason
            }
        }
        share.pipes.values.forEach { it.finish(false) }
        checkFinished(share)
        changed(share, force = true)
    }

    private fun checkFinished(share: Share) {
        synchronized(share) {
            if (share.finished) return
            if (!share.recipients.values.all { it.state in terminal }) return
            if (share.upload == UploadState.WAITING || share.upload == UploadState.REQUESTED || share.upload == UploadState.UPLOADING) {
                share.upload = UploadState.CANCELLED
            }
            share.finishedAt = elapsed()
        }
        cacheDir(share).deleteRecursively()
    }

    // --- Upload from the sending browser ---------------------------------

    internal fun receiveUpload(share: Share, index: Int, input: InputStream, length: Long) {
        val file = share.files.getOrNull(index) ?: throw IllegalArgumentException("No such file")
        if (length != file.size) throw IllegalArgumentException("Size doesn't match")
        synchronized(share) {
            if (share.cancelled) throw IllegalStateException("This send was cancelled")
            if (share.upload != UploadState.REQUESTED && share.upload != UploadState.UPLOADING) {
                throw IllegalStateException("Waiting for the receiver to accept")
            }
            if (file.received) throw IllegalStateException("Already uploaded")
            share.upload = UploadState.UPLOADING
        }
        changed(share, force = true)
        try {
            when (share.mode) {
                Mode.RELAY -> {
                    val pipe = share.pipe(index)
                    pipe.offer(input)
                    if (!pipe.awaitFinished { share.cancelled }) throw IOException("The receiver stopped the transfer")
                }
                Mode.DIRECT -> {
                    val r = share.recipients.getValue(PHONE_ID)
                    r.state = RecipientState.RECEIVING
                    val target = Names.reserveUnique(File(FileTransfer.receivedDir, file.name))
                    val transfer = FileTransfer.begin(target.name, Direction.TO_PHONE, length, share.fromName)
                    writeFile(input, target, length, transfer) { n ->
                        share.uploaded += n
                        r.done += n
                        changed(share)
                    }
                    file.file = target
                    scan(listOf(target.path))
                }
                Mode.STORED -> {
                    val target = File(cacheDir(share), index.toString())
                    val transfer = FileTransfer.begin(file.name.substringAfterLast('/'), Direction.TO_PHONE, length, share.fromName)
                    writeFile(input, target, length, transfer, reserve = false) { n ->
                        share.uploaded += n
                        changed(share)
                    }
                    file.file = target
                }
                Mode.SOURCE -> throw IllegalStateException("Nothing to upload")
            }
            file.received = true
            if (share.files.all { it.received }) uploadComplete(share)
            changed(share, force = true)
        } catch (e: Exception) {
            if (!share.cancelled) failShare(share, "The upload stopped")
            throw e
        }
    }

    private fun uploadComplete(share: Share) {
        share.upload = UploadState.DONE
        when (share.mode) {
            Mode.DIRECT -> {
                share.recipients.getValue(PHONE_ID).state = RecipientState.DONE
                FileTransfer.log("Received ${share.title} from ${share.fromName} in Download/PhoneDeck")
                checkFinished(share)
            }
            Mode.STORED -> {
                val phone = share.recipients[PHONE_ID]
                if (phone?.state == RecipientState.ACCEPTED) copyToPhone(share)
            }
            else -> Unit
        }
    }

    /** STORED share accepted by the phone: copy the uploaded files into Download/PhoneDeck. */
    private fun copyToPhone(share: Share) {
        val r = share.recipients[PHONE_ID] ?: return
        synchronized(share) {
            if (r.state != RecipientState.ACCEPTED) return
            r.state = RecipientState.RECEIVING
        }
        changed(share, force = true)
        thread(name = "share-copy") {
            val transfer = FileTransfer.begin(share.title, Direction.TO_PHONE, share.total, share.fromName)
            var ok = false
            val saved = mutableListOf<String>()
            try {
                share.files.forEach { f ->
                    val target = Names.reserveUnique(File(FileTransfer.receivedDir, f.name))
                    val source = f.file ?: throw IOException("Missing file")
                    FileInputStream(source).use { input ->
                        writeFile(input, target, f.size, null) { n ->
                            r.done += n
                            transfer.done += n
                            changed(share)
                        }
                    }
                    saved += target.path
                }
                ok = true
                r.state = RecipientState.DONE
                FileTransfer.log("Received ${share.title} from ${share.fromName} in Download/PhoneDeck")
            } catch (e: Exception) {
                r.state = RecipientState.FAILED
                r.error = "Couldn't save: ${e.message}"
            } finally {
                FileTransfer.end(transfer, ok)
                scan(saved)
                checkFinished(share)
                changed(share, force = true)
            }
        }
    }

    // --- Download by a receiving browser ----------------------------------

    /**
     * Streams the share to a recipient browser. One file keeps its name and size; several files
     * become one zip (folders keep their structure). [open] writes the response headers.
     */
    internal fun download(share: Share, device: Device, open: (name: String, size: Long?, zip: Boolean) -> OutputStream) {
        val r = share.recipients[device.id] ?: throw SecurityException("This send isn't for you")
        synchronized(share) {
            if (share.cancelled) throw IllegalStateException("This send was cancelled")
            val again = r.state == RecipientState.DONE || r.state == RecipientState.FAILED
            when {
                r.state == RecipientState.ACCEPTED -> Unit
                again && share.mode != Mode.RELAY -> Unit
                else -> throw IllegalStateException("Accept the send first")
            }
            if (share.mode == Mode.STORED && share.upload != UploadState.DONE) throw IllegalStateException("Still uploading")
            r.state = RecipientState.RECEIVING
            r.done = 0
            r.error = null
        }
        changed(share, force = true)

        val single = share.files.size == 1
        val name = if (single) share.files[0].name.substringAfterLast('/') else zipName(share)
        val direction = if (share.mode == Mode.SOURCE) Direction.FROM_PHONE else Direction.RELAY
        val transfer = FileTransfer.begin(name, direction, share.total, "${share.fromName} → ${r.name}")
        var ok = false
        try {
            val out = open(name, if (single) share.files[0].size.takeIf { it >= 0 } else null, !single)
            if (single) {
                streamFile(share, share.files[0], out, r, transfer)
            } else {
                val zip = ZipOutputStream(out)
                zip.setLevel(Deflater.NO_COMPRESSION)
                share.files.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    streamFile(share, f, zip, r, transfer)
                    zip.closeEntry()
                }
                zip.finish()
            }
            out.flush()
            ok = true
            r.state = RecipientState.DONE
        } catch (e: Exception) {
            if (r.state !in terminal) {
                r.state = RecipientState.FAILED
                r.error = "The download stopped"
            }
            if (share.mode == Mode.RELAY) failShare(share, "The download stopped")
            throw e
        } finally {
            FileTransfer.end(transfer, ok)
            checkFinished(share)
            changed(share, force = true)
        }
    }

    private fun streamFile(share: Share, file: ShareFile, out: OutputStream, r: Recipient, transfer: FileTransfer.Transfer) {
        val relay = share.mode == Mode.RELAY
        val pipe = if (relay) share.pipe(file.index) else null
        val input: InputStream = when (share.mode) {
            Mode.RELAY -> pipe!!.awaitInput { share.cancelled }
            Mode.STORED -> FileInputStream(file.file ?: throw IOException("Missing file"))
            Mode.SOURCE -> context.contentResolver.openInputStream(file.uri!!) ?: throw IOException("Can't read ${file.name}")
            Mode.DIRECT -> throw IllegalStateException("Nothing to download")
        }
        var copied = 0L
        try {
            val buffer = ByteArray(BUFFER)
            val limit = if (file.size >= 0) file.size else Long.MAX_VALUE
            while (copied < limit && !share.cancelled) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - copied).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                copied += n
                r.done += n
                transfer.done += n
                if (relay) {
                    share.uploaded += n
                    FileTransfer.bytesReceived.addAndGet(n.toLong())
                }
                FileTransfer.bytesSent.addAndGet(n.toLong())
                changed(share)
            }
            if (share.cancelled) throw IOException("Cancelled")
            if (file.size >= 0 && copied < file.size) throw IOException("The sender stopped")
            pipe?.finish(true)
        } catch (e: Exception) {
            pipe?.finish(false)
            throw e
        } finally {
            if (!relay) runCatching { input.close() }
        }
        if (relay) {
            file.received = true
            if (share.files.all { it.received }) share.upload = UploadState.DONE
        }
    }

    private fun zipName(share: Share): String {
        val from = share.fromName.replace(Regex("[^\\w .()-]"), "").trim().ifEmpty { "device" }
        return "From $from - ${share.files.size} files.zip"
    }

    private fun writeFile(
        input: InputStream, target: File, length: Long, transfer: FileTransfer.Transfer?,
        reserve: Boolean = true, onChunk: (Int) -> Unit,
    ) {
        val parent = target.parentFile ?: throw IOException("Invalid path")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Can't create ${parent.name}")
        if (length > parent.usableSpace) throw IOException("Not enough free space on the phone")
        val temp = File(parent, ".${target.name}.part")
        var ok = false
        try {
            var received = 0L
            val buffer = ByteArray(BUFFER)
            FileOutputStream(temp).use { output ->
                while (received < length) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt())
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    received += n
                    transfer?.let { it.done += n }
                    FileTransfer.bytesReceived.addAndGet(n.toLong())
                    onChunk(n)
                }
            }
            if (received < length) throw IOException("Upload interrupted")
            if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
            ok = true
        } finally {
            if (!ok) temp.delete()
            if (reserve) Names.release(target)
            transfer?.let { FileTransfer.end(it, ok) }
        }
    }

    private fun scan(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    /** Pushes the share's state to everyone involved; progress-only updates are throttled. */
    private fun changed(share: Share, force: Boolean = false) {
        val now = elapsed()
        if (!force && now - share.lastEmit < 300) return
        share.lastEmit = now
        val json = shareJson(share)
        emitTo(share.recipients.keys + share.fromId, json)
        if (share.fromId == PHONE_ID || share.recipients.containsKey(PHONE_ID)) listener?.onShareChanged(share)
    }

    private fun shareJson(share: Share): JSONObject {
        val files = JSONArray()
        share.files.take(50).forEach { files.put(JSONObject().put("name", it.name).put("size", it.size)) }
        val recipients = JSONArray()
        share.recipients.values.forEach {
            recipients.put(JSONObject().put("id", it.deviceId).put("name", it.name).put("state", it.state.name)
                .put("done", it.done).put("error", it.error ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("type", "share")
            .put("id", share.id)
            .put("from", JSONObject().put("id", share.fromId).put("name", share.fromName))
            .put("mode", share.mode.name)
            .put("total", share.total)
            .put("count", share.files.size)
            .put("files", files)
            .put("upload", JSONObject().put("state", share.upload.name).put("done", share.uploaded))
            .put("recipients", recipients)
            .put("created", share.createdAt)
            .put("finished", share.finished)
    }

    // ------------------------------------------------------------------
    // Text messages
    // ------------------------------------------------------------------

    class TextMessage internal constructor(
        val id: String,
        val fromId: String,
        val fromName: String,
        /** (device id, name) */
        val to: List<Pair<String, String>>,
        val text: String,
        val time: Long,
    )

    private val texts = CopyOnWriteArrayList<TextMessage>()

    fun texts(): List<TextMessage> = texts.toList()

    internal fun sendText(fromId: String, fromName: String, to: List<String>, text: String): TextMessage {
        val clean = text.trimEnd()
        if (clean.isBlank()) throw IllegalArgumentException("Type something to send")
        if (clean.length > MAX_TEXT) throw IllegalArgumentException("Text is too long (max $MAX_TEXT characters)")
        val recipients = recipientsFor(fromId, to).values.map { it.deviceId to it.name }
        val message = TextMessage(randomHex(8), fromId, fromName, recipients, clean, System.currentTimeMillis())
        texts.add(0, message)
        while (texts.size > TEXT_HISTORY) texts.removeAt(texts.size - 1)
        emitTo(recipients.map { it.first } + fromId, textJson(message))
        if (recipients.any { it.first == PHONE_ID }) listener?.onText(message)
        return message
    }

    fun sendTextFromPhone(to: List<String>, text: String) = sendText(PHONE_ID, phoneName, to, text)

    private fun textJson(t: TextMessage): JSONObject {
        val to = JSONArray()
        t.to.forEach { to.put(JSONObject().put("id", it.first).put("name", it.second)) }
        return JSONObject().put("type", "text").put("id", t.id)
            .put("from", JSONObject().put("id", t.fromId).put("name", t.fromName))
            .put("to", to).put("text", t.text).put("time", t.time)
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    /** Expires old requests and shares and notices devices going offline. Called every second. */
    fun cleanup(force: Boolean = false) {
        val now = elapsed()
        requests.values.forEach {
            if (it.status == RequestStatus.PENDING && now - it.created > REQUEST_TTL_MS) close(it, RequestStatus.EXPIRED)
        }
        requests.values.removeAll { it.status != RequestStatus.PENDING && now - it.created > REQUEST_TTL_MS * 2 }
        shares.values.forEach { share ->
            if (!share.finished && now - share.created > SHARE_TTL_MS) cancelShare(share)
        }
        val keep = if (force) 0L else FINISHED_KEEP_MS
        shares.values.removeAll { it.finished && now - it.finishedAt > keep }
        val key = onlineDevices().joinToString { it.id + it.name }
        if (key != onlineKey) broadcastDevices()
    }

    /** Server stopping: cancel what's in flight, delete temporary copies, end sessions of non-remembered devices. */
    fun shutdown() {
        shares.values.filter { !it.finished }.forEach { cancelShare(it) }
        pendingRequests().forEach { close(it, RequestStatus.EXPIRED) }
        devices.values.forEach { d -> d.streams.forEach { it.offer(KICKED) } }
        devices.values.filter { !it.remembered }.forEach {
            devices.remove(it.id)
            byToken.remove(it.tokenHash)
        }
        saveRemembered()
        sharesRoot.deleteRecursively()
    }

    private fun formatSize(bytes: Long) = com.truckcontroller.pro.formatBytes(bytes)
}
