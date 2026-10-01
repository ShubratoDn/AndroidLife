package com.truckcontroller.pro.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.truckcontroller.pro.transfer.FileTransfer.Access
import com.truckcontroller.pro.transfer.FileTransfer.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server for [FileTransfer]: serves the browser page from assets and a JSON API.
 *
 * Security model:
 * - only devices on the local network are answered, and only when they address the phone by IP
 *   (blocks DNS-rebinding pages),
 * - a browser has no access until the phone approves it (or it enters the PIN); it then holds an
 *   HttpOnly, SameSite=Strict session cookie tied to a [Hub.Device],
 * - every file operation is limited to the folders that device's [Access] level allows.
 *
 * Files are streamed in both directions (never held in memory), downloads support Range so
 * videos can be seeked, and folders download as zip.
 */
class TransferServer(private val context: Context, private val hub: Hub) {

    private class Volume(val name: String, val root: File)

    /** A top folder a device may open. */
    private class Root(val name: String, val dir: File, val writable: Boolean, val icon: String)

    private class HttpError(val status: Int, message: String) : Exception(message)

    /** Request body limited to Content-Length, so the connection can be reused afterwards. */
    private class Body(private val input: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining-- else remaining = -1
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n else remaining = -1
            return n
        }

        fun text(limit: Int): String {
            if (remaining > limit) throw HttpError(413, "Request too large")
            return readBytes().toString(Charsets.UTF_8)
        }

        fun json(limit: Int): JSONObject = try {
            JSONObject(text(limit))
        } catch (e: org.json.JSONException) {
            throw HttpError(400, "Bad request")
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, List<String>>,
        val headers: Map<String, String>,
        val body: Body,
        val client: String,
        val keepAlive: Boolean,
    ) {
        fun param(name: String) = query[name]?.firstOrNull()
        val isHead get() = method == "HEAD"
        val token get() = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')
    }

    companion object {
        private const val COOKIE = "pd"
        private const val REMEMBER_SECONDS = 30L * 24 * 60 * 60
        private const val BOUNDARY = "phonedeckframe"
        private const val BUFFER = 256 * 1024
        private const val THUMB = 320
        private const val MAX_PREVIEW = 2048
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        private const val PAGE_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; " +
            "connect-src 'self'; frame-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

        /** Standard folders offered as shortcuts to devices with full access. */
        private val PLACES = listOf(
            Triple("Camera", "DCIM/Camera", "camera"),
            Triple("Screenshots", "Pictures/Screenshots", "image"),
            Triple("Screenshots", "DCIM/Screenshots", "image"),
            Triple("Pictures", "Pictures", "image"),
            Triple("Download", "Download", "download"),
            Triple("Documents", "Documents", "doc"),
            Triple("Music", "Music", "music"),
            Triple("Movies", "Movies", "film"),
            Triple("Recordings", "Recordings", "mic"),
            Triple("WhatsApp", "Android/media/com.whatsapp/WhatsApp/Media", "chat"),
            Triple("Telegram", "Telegram", "chat"),
        )
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(0, 64, 60, TimeUnit.SECONDS, SynchronousQueue())
    private val page: ByteArray by lazy { context.assets.open("transfer/index.html").use { it.readBytes() } }

    private val thumbs = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    /** Thumbnails decode whole images; a few at a time keeps memory in check. */
    private val thumbSlots = Semaphore(2)

    private var cachedVolumes: List<Volume> = emptyList()
    private var volumesAt = 0L

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Binds the first free port from [firstPort] and starts accepting. Returns the port. */
    fun start(firstPort: Int): Int {
        var lastError: IOException? = null
        for (port in firstPort until firstPort + 10) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port), 50)
            } catch (e: IOException) {
                socket.close()
                lastError = e
                continue
            }
            serverSocket = socket
            running = true
            thread(name = "transfer-accept", isDaemon = true) { acceptLoop(socket) }
            return port
        }
        throw lastError ?: IOException("No free port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                break
            }
            if (!isLocal(socket.inetAddress)) {
                runCatching { socket.close() }
                continue
            }
            try {
                pool.execute { serve(socket) }
            } catch (e: RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    /** Local network only: private IPv4 ranges, link-local and unique-local IPv6. */
    private fun isLocal(a: InetAddress): Boolean {
        val bytes = a.address
        // IPv4 client seen through the dual-stack socket as ::ffff:a.b.c.d
        if (a is Inet6Address && bytes.size == 16 && (0 until 10).all { bytes[it].toInt() == 0 } &&
            bytes[10].toInt() == -1 && bytes[11].toInt() == -1
        ) {
            return isLocal(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
            (a is Inet6Address && (bytes[0].toInt() and 0xFE) == 0xFC)
    }

    private fun serve(socket: Socket) {
        sockets += socket
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val client = socket.inetAddress.hostAddress.orEmpty().removePrefix("::ffff:")
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (running) {
                val request = try {
                    readRequest(input, client) ?: break
                } catch (e: HttpError) {
                    sendJson(output, null, e.status, JSONObject().put("error", e.message), keepAlive = false)
                    output.flush()
                    break
                }
                FileTransfer.seen()
                val keepAlive = handle(request, output)
                output.flush()
                if (!keepAlive || !request.keepAlive || request.body.remaining != 0L) break
            }
        } catch (_: Exception) {
            // Browser closed the connection, it timed out or the server stopped
        } finally {
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------
    // HTTP parsing and responses
    // ------------------------------------------------------------------

    private fun readLine(input: InputStream, limit: Int): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            if (c != '\r'.code) line.append(c.toChar())
            if (line.length > limit) throw HttpError(431, "Request header too large")
        }
    }

    private fun readRequest(input: InputStream, client: String): Request? {
        var line = readLine(input, 64 * 1024) ?: return null
        if (line.isEmpty()) line = readLine(input, 64 * 1024) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) throw HttpError(400, "Bad request")
        val (method, target, version) = parts
        val headers = HashMap<String, String>()
        var size = 0
        while (true) {
            val header = readLine(input, 16 * 1024) ?: return null
            if (header.isEmpty()) break
            size += header.length
            if (size > 32 * 1024) throw HttpError(431, "Request header too large")
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase(Locale.US)] = header.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(411, "Send the file size with the upload")
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw HttpError(400, "Bad length") } ?: 0L
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = parseForm(if (q < 0) "" else target.substring(q + 1))
        val keepAlive = version == "HTTP/1.1" && !headers["connection"].equals("close", ignoreCase = true)
        return Request(method.uppercase(Locale.US), path, query, headers, Body(input, length), client, keepAlive)
    }

    private fun parseForm(text: String): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        text.split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val eq = pair.indexOf('=')
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
            result.getOrPut(key) { mutableListOf() } += value
        }
        return result
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw HttpError(400, "Bad encoding")
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"
        507 -> "Insufficient Storage"
        else -> "Error"
    }

    private fun writeHead(out: OutputStream, status: Int, headers: List<Pair<String, String>>, keepAlive: Boolean) {
        val head = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("X-Frame-Options: DENY\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendBytes(
        out: OutputStream, req: Request?, status: Int, type: String, body: ByteArray,
        extra: List<Pair<String, String>> = emptyList(), keepAlive: Boolean = true,
    ): Boolean {
        writeHead(out, status, listOf("Content-Type" to type, "Content-Length" to body.size.toString()) + extra, keepAlive)
        if (req?.isHead != true) out.write(body)
        return keepAlive
    }

    private fun sendJson(
        out: OutputStream, req: Request?, status: Int, json: Any,
        extra: List<Pair<String, String>> = emptyList(), keepAlive: Boolean = true,
    ) = sendBytes(out, req, status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8),
        listOf("Cache-Control" to "no-store") + extra, keepAlive)

    private fun ok(out: OutputStream, req: Request, json: JSONObject = JSONObject().put("ok", true)) =
        sendJson(out, req, 200, json)

    private fun sessionCookie(token: String, remember: Boolean) =
        "Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict" + if (remember) "; Max-Age=$REMEMBER_SECONDS" else ""

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    private fun handle(req: Request, out: OutputStream): Boolean = try {
        route(req, out)
    } catch (e: HttpError) {
        sendJson(out, req, e.status, JSONObject().put("error", e.message))
    } catch (e: Hub.LimitException) {
        sendJson(out, req, 429, JSONObject().put("error", e.message))
    } catch (e: IllegalArgumentException) {
        sendJson(out, req, 400, JSONObject().put("error", e.message ?: "Bad request"))
    } catch (e: IllegalStateException) {
        sendJson(out, req, 409, JSONObject().put("error", e.message ?: "Not possible right now"))
    } catch (e: SecurityException) {
        sendJson(out, req, 403, JSONObject().put("error", "Android denied access to this file"))
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        // Unexpected error: answer if nothing was sent yet, then drop the connection
        sendJson(out, req, 500, JSONObject().put("error", e.message ?: "Server error"), keepAlive = false)
    }

    /** The page must be opened by IP: a hostname means another site is trying to reach the phone. */
    private fun checkHost(req: Request) {
        val host = req.headers["host"] ?: throw HttpError(400, "Bad request")
        val name = if (host.startsWith("[")) host.substringBefore(']').removePrefix("[") else host.substringBefore(':')
        if (!(name == "localhost" || IPV4.matches(name) || host.startsWith("["))) {
            throw HttpError(403, "Open this page with the phone's IP address")
        }
    }

    private fun route(req: Request, out: OutputStream): Boolean {
        checkHost(req)
        val get = req.method == "GET" || req.method == "HEAD"
        val post = req.method == "POST"
        if (req.path == "/" || req.path == "/index.html") {
            if (!get) throw HttpError(405, "Method not allowed")
            return sendBytes(out, req, 200, "text/html; charset=utf-8", page,
                listOf("Cache-Control" to "no-cache", "Content-Security-Policy" to PAGE_POLICY))
        }
        if (!req.path.startsWith("/api/")) throw HttpError(404, "Not found")

        // Before sign-in
        when {
            req.path == "/api/hello" && get -> return hello(req, out)
            req.path == "/api/auth/request" && post -> return authRequest(req, out)
            req.path == "/api/auth/wait" && get -> return authWait(req, out)
            req.path == "/api/auth/pin" && post -> return authPin(req, out)
        }

        val device = hub.deviceForToken(req.token) ?: throw HttpError(401, "Not connected")
        hub.touch(device, req.client)
        return when {
            req.path == "/api/me" && get -> me(req, out, device)
            req.path == "/api/me/name" && post -> renameMe(req, out, device)
            req.path == "/api/logout" && post -> logout(req, out, device)
            req.path == "/api/events" && get -> events(out, device)
            req.path == "/api/shares" && post -> createShare(req, out, device)
            req.path == "/api/shares/accept" && post -> shareAction(req, out, device, "accept")
            req.path == "/api/shares/decline" && post -> shareAction(req, out, device, "decline")
            req.path == "/api/shares/cancel" && post -> shareAction(req, out, device, "cancel")
            req.path == "/api/shares/upload" && req.method == "PUT" -> shareUpload(req, out, device)
            req.path == "/api/shares/download" && get -> shareDownload(req, out, device)
            req.path == "/api/text" && post -> text(req, out, device)
            req.path == "/api/live/stream" && get -> liveStream(req, out, device)
            req.path == "/api/live/snapshot" && get -> liveSnapshot(req, out, device)
            req.path == "/api/live/control" && post -> liveControl(req, out)
            req.path == "/api/list" && get -> list(req, out, device)
            req.path == "/api/file" && get -> file(req, out, device)
            req.path == "/api/thumb" && get -> thumb(req, out, device)
            req.path == "/api/zip" && (get || post) -> zip(req, out, device)
            req.path == "/api/upload" && req.method == "PUT" -> upload(req, out, device)
            req.path == "/api/mkdir" && post -> mkdir(req, out, device)
            req.path == "/api/rename" && post -> rename(req, out, device)
            req.path == "/api/delete" && post -> delete(req, out, device)
            else -> throw HttpError(404, "Not found")
        }
    }

    // ------------------------------------------------------------------
    // Sign-in: approval on the phone, or the PIN
    // ------------------------------------------------------------------

    private fun hello(req: Request, out: OutputStream): Boolean =
        ok(out, req, JSONObject().put("phone", hub.phoneName).put("pin", FileTransfer.allowPin(context)))

    private fun authRequest(req: Request, out: OutputStream): Boolean {
        val body = req.body.json(4096)
        val request = hub.requestAccess(body.optString("name"), body.optString("platform"), req.client)
        return ok(out, req, JSONObject().put("id", request.id).put("code", request.code))
    }

    /** Long poll: answers when the phone decides, or after 25 s with "pending". */
    private fun authWait(req: Request, out: OutputStream): Boolean {
        val request = hub.awaitRequest(req.param("id"), req.client, 25_000)
            ?: return sendJson(out, req, 404, JSONObject().put("status", "unknown"))
        val status = request.status.name.lowercase(Locale.US)
        if (request.status != Hub.RequestStatus.APPROVED) return ok(out, req, JSONObject().put("status", status))
        val token = hub.collectToken(request)
        val cookie = if (token != null) listOf(sessionCookie(token, request.remembered)) else emptyList()
        return sendJson(out, req, 200, JSONObject().put("status", status), cookie)
    }

    private fun authPin(req: Request, out: OutputStream): Boolean {
        val body = req.body.json(4096)
        val (_, token) = hub.loginWithPin(body.optString("pin").trim(), body.optString("name"), body.optString("platform"), req.client)
            ?: throw HttpError(403, "Wrong PIN")
        return sendJson(out, req, 200, JSONObject().put("ok", true), listOf(sessionCookie(token, false)))
    }

    private fun logout(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        hub.remove(device.id, byPhone = false)
        return sendJson(out, req, 200, JSONObject().put("ok", true),
            listOf("Set-Cookie" to "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))
    }

    private fun me(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val roots = JSONArray()
        roots(device).forEach {
            roots.put(JSONObject().put("name", it.name).put("path", it.dir.path).put("writable", it.writable)
                .put("icon", it.icon).put("free", it.dir.usableSpace).put("total", it.dir.totalSpace))
        }
        val places = JSONArray()
        if (device.access == Access.FULL) {
            val added = HashSet<String>()
            val internal = volumes().first().root
            PLACES.forEach { (name, rel, icon) ->
                val dir = File(internal, rel)
                // First existing folder wins (Screenshots live in Pictures or DCIM depending on the phone)
                if (name !in added && dir.isDirectory) {
                    added += name
                    places.put(JSONObject().put("name", name).put("path", dir.path).put("icon", icon))
                }
            }
        }
        return ok(out, req, JSONObject()
            .put("me", hub.meJson(device))
            .put("phone", hub.phoneName)
            .put("roots", roots)
            .put("places", places))
    }

    private fun renameMe(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        hub.rename(device, req.body.json(4096).optString("name"))
        return ok(out, req)
    }

    // ------------------------------------------------------------------
    // Live events (Server-Sent Events)
    // ------------------------------------------------------------------

    private fun events(out: OutputStream, device: Hub.Device): Boolean {
        writeHead(out, 200, listOf(
            "Content-Type" to "text/event-stream; charset=utf-8",
            "Cache-Control" to "no-store",
        ), keepAlive = false)
        out.write("retry: 3000\n\n".toByteArray())
        out.flush()
        val queue = hub.openStream(device)
        try {
            while (running && hub.isCurrent(device)) {
                val message = queue.poll(15, TimeUnit.SECONDS)
                if (message == null) {
                    out.write(": ping\n\n".toByteArray())
                } else {
                    out.write("data: $message\n\n".toByteArray(Charsets.UTF_8))
                    if (message == Hub.KICKED) break
                }
                out.flush()
            }
            if (!hub.isCurrent(device)) {
                out.write("data: ${Hub.KICKED}\n\n".toByteArray())
                out.flush()
            }
        } finally {
            hub.closeStream(device, queue)
        }
        return false
    }

    // ------------------------------------------------------------------
    // Sending between devices
    // ------------------------------------------------------------------

    private fun createShare(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val body = req.body.json(4 * 1024 * 1024)
        val to = body.optJSONArray("to") ?: throw HttpError(400, "Choose who to send to")
        val files = body.optJSONArray("files") ?: throw HttpError(400, "No files")
        val share = hub.createShare(
            device,
            (0 until files.length()).map { files.getJSONObject(it).let { f -> f.optString("name") to f.optLong("size", -1) } },
            (0 until to.length()).map { to.getString(it) },
        )
        return ok(out, req, JSONObject().put("ok", true).put("id", share.id))
    }

    private fun shareAction(req: Request, out: OutputStream, device: Hub.Device, action: String): Boolean {
        val share = hub.share(req.param("id")) ?: throw HttpError(404, "This send no longer exists")
        val isSender = share.fromId == device.id
        val isRecipient = share.recipients.containsKey(device.id)
        if (!isSender && !isRecipient) throw HttpError(403, "This send isn't yours")
        when (action) {
            "accept" -> if (!isRecipient || !hub.accept(share, device.id)) throw HttpError(409, "Can't accept this send any more")
            "decline" -> if (isRecipient) hub.decline(share, device.id)
            "cancel" -> if (isSender) hub.cancelShare(share) else hub.decline(share, device.id)
        }
        return ok(out, req)
    }

    private fun shareUpload(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val share = hub.share(req.param("id")) ?: throw HttpError(404, "This send no longer exists")
        if (share.fromId != device.id) throw HttpError(403, "Only the sender can upload")
        val index = req.param("index")?.toIntOrNull() ?: throw HttpError(400, "Missing file")
        val length = req.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Missing file size")
        hub.receiveUpload(share, index, req.body, length)
        return ok(out, req)
    }

    private fun shareDownload(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val share = hub.share(req.param("id")) ?: throw HttpError(404, "This send no longer exists")
        if (!share.recipients.containsKey(device.id)) throw HttpError(403, "This send isn't for you")
        var keepAlive = true
        hub.download(share, device) { name, size, zip ->
            keepAlive = !zip && size != null
            val headers = mutableListOf(
                "Content-Type" to if (zip) "application/zip" else Names.mime(name),
                "Content-Disposition" to disposition("attachment", name),
                "Cache-Control" to "no-store",
            )
            if (size != null) headers += "Content-Length" to size.toString()
            writeHead(out, 200, headers, keepAlive)
            out
        }
        return keepAlive
    }

    private fun text(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val body = req.body.json(64 * 1024)
        val to = body.optJSONArray("to") ?: throw HttpError(400, "Choose who to send to")
        hub.sendText(device.id, device.name, (0 until to.length()).map { to.getString(it) }, body.optString("text"))
        return ok(out, req)
    }

    // ------------------------------------------------------------------
    // Live view: camera / screen as MJPEG
    // ------------------------------------------------------------------

    private fun liveSource(req: Request): LiveSource {
        val source = LiveShare.source(req.param("src")) ?: throw HttpError(404, "Unknown source")
        if (!source.on) throw HttpError(409, "The phone isn't sharing its ${source.id} right now")
        return source
    }

    /** Endless multipart response: each part is one JPEG frame (browsers show it in an <img>). */
    private fun liveStream(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val source = liveSource(req)
        writeHead(out, 200, listOf(
            "Content-Type" to "multipart/x-mixed-replace; boundary=$BOUNDARY",
            "Cache-Control" to "no-store",
        ), keepAlive = false)
        out.flush()
        val viewer = source.addViewer(device.name)
        try {
            var seq = 0L
            while (running && source.on && hub.isCurrent(device)) {
                val (next, frame) = source.await(seq, 2_000) ?: continue
                seq = next
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
                FileTransfer.bytesSent.addAndGet(frame.size.toLong())
            }
        } finally {
            source.removeViewer(viewer)
        }
        return false
    }

    private fun liveSnapshot(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val source = liveSource(req)
        // Camera: a real full-resolution photo when the phone can take one next to the stream
        val photo = if (source === LiveShare.camera) LiveShare.takePhoto?.let { take ->
            val done = java.util.concurrent.CountDownLatch(1)
            var bytes: ByteArray? = null
            take { bytes = it; done.countDown() }
            done.await(8, TimeUnit.SECONDS)
            bytes
        } else null
        val frame = photo ?: source.snapshot(3_000) ?: throw HttpError(503, "No picture yet, try again")
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd HH-mm-ss", System.currentTimeMillis())
        FileTransfer.log("${device.name} saved a snapshot of the ${source.id}")
        return sendBytes(out, req, 200, "image/jpeg", frame, listOf(
            "Content-Disposition" to disposition("attachment", "${source.id} $stamp.jpg"),
            "Cache-Control" to "no-store",
        ))
    }

    private fun liveControl(req: Request, out: OutputStream): Boolean {
        val source = liveSource(req)
        val action = req.param("action")
        if (source !== LiveShare.camera || action !in setOf("lens", "torch", "rotate")) throw HttpError(400, "Unknown action")
        val control = LiveShare.control ?: throw HttpError(409, "Not available")
        control(source.id, action!!)
        return ok(out, req)
    }

    // ------------------------------------------------------------------
    // Storage volumes and access scope
    // ------------------------------------------------------------------

    @Synchronized
    private fun volumes(): List<Volume> {
        val now = SystemClock.elapsedRealtime()
        if (cachedVolumes.isNotEmpty() && now - volumesAt < 5_000) return cachedVolumes
        val list = mutableListOf(Volume("Internal storage", Environment.getExternalStorageDirectory().canonicalFile))
        val storage = context.getSystemService(StorageManager::class.java)
        // Removable cards: the app's own folder on each card reveals the card's root
        context.getExternalFilesDirs(null).filterNotNull().drop(1).forEach { dir ->
            val i = dir.absolutePath.indexOf("/Android/data/")
            if (i <= 0) return@forEach
            val root = File(dir.absolutePath.substring(0, i))
            if (!root.canRead()) return@forEach
            val name = runCatching { storage.getStorageVolume(root)?.getDescription(context) }.getOrNull() ?: "SD card"
            list += Volume(name, root.canonicalFile)
        }
        cachedVolumes = list
        volumesAt = now
        return list
    }

    private fun inStorage(file: File) =
        volumes().any { file == it.root || file.path.startsWith(it.root.path + "/") }

    /** The top folders this device may open: none, the shared folders, or every storage volume. */
    private fun roots(device: Hub.Device): List<Root> = when (device.access) {
        Access.SEND -> emptyList()
        Access.FOLDERS -> FileTransfer.sharedFolders(context).mapNotNull { folder ->
            val dir = runCatching { File(folder.path).canonicalFile }.getOrNull()
                ?.takeIf { it.isDirectory && inStorage(it) } ?: return@mapNotNull null
            Root(dir.name, dir, folder.writable, "folder")
        }
        Access.FULL -> volumes().mapIndexed { i, v -> Root(v.name, v.root, true, if (i == 0) "phone" else "sd") }
    }

    /** Resolves a path from the browser inside the device's allowed folders, or refuses it. */
    private fun resolve(device: Hub.Device, path: String?): Pair<File, Root> {
        if (device.access == Access.SEND) throw HttpError(403, "The phone hasn't shared its files with this device")
        if (path.isNullOrEmpty()) throw HttpError(400, "Missing path")
        val file = File(path).canonicalFile
        val root = roots(device).filter { file == it.dir || file.path.startsWith(it.dir.path + "/") }
            .maxByOrNull { it.dir.path.length }
            ?: throw HttpError(403, "This device doesn't have access to that folder")
        return file to root
    }

    private fun requireWritable(root: Root) {
        if (!root.writable) throw HttpError(403, "\"${root.name}\" is view-only for this device")
    }

    private fun disposition(kind: String, name: String): String {
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "$kind; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun scan(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    private fun cleanName(name: String?) = Names.clean(name) ?: throw HttpError(400, "Invalid name")

    // ------------------------------------------------------------------
    // API: browsing the phone
    // ------------------------------------------------------------------

    private fun list(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val first = roots(device).firstOrNull() ?: throw HttpError(403, "The phone hasn't shared its files with this device")
        val (dir, root) = resolve(device, req.param("path") ?: first.dir.path)
        if (!dir.isDirectory) throw HttpError(404, "Folder not found")
        val children = dir.listFiles() ?: throw HttpError(403, "Android does not allow opening this folder")
        val items = JSONArray()
        children.forEach { f ->
            val isDir = f.isDirectory
            items.put(JSONObject()
                .put("n", f.name)
                .put("d", isDir)
                .put("s", if (isDir) (f.list()?.size ?: 0).toLong() else f.length())
                .put("m", f.lastModified()))
        }
        val isRoot = dir == root.dir
        return ok(out, req, JSONObject()
            .put("path", dir.path)
            .put("name", if (isRoot) root.name else dir.name)
            .put("root", root.dir.path)
            .put("volume", root.name)
            .put("parent", if (isRoot) JSONObject.NULL else dir.parent)
            .put("free", root.dir.usableSpace)
            .put("total", root.dir.totalSpace)
            .put("writable", root.writable && dir.canWrite())
            .put("items", items))
    }

    private fun file(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val (file, _) = resolve(device, req.param("path"))
        if (!file.isFile) throw HttpError(404, "File not found")
        if (!file.canRead()) throw HttpError(403, "Android does not allow reading this file")
        val download = req.param("dl") == "1"
        val length = file.length()
        val type = Names.mime(file.name)

        var start = 0L
        var end = length - 1
        var partial = false
        req.headers["range"]?.takeIf { it.startsWith("bytes=") && !it.contains(',') }?.let { range ->
            val (a, b) = range.removePrefix("bytes=").split('-', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            when {
                a.isEmpty() && b.isNotEmpty() -> start = (length - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
                a.isNotEmpty() -> {
                    start = a.toLongOrNull() ?: 0
                    if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, length - 1)
                }
            }
            if (start >= length || start > end) {
                writeHead(out, 416, listOf("Content-Range" to "bytes */$length", "Content-Length" to "0"), true)
                return true
            }
            partial = true
        }

        val count = end - start + 1
        val headers = mutableListOf(
            "Content-Type" to type,
            "Content-Length" to count.coerceAtLeast(0).toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "private, no-cache",
            "Content-Disposition" to disposition(if (download) "attachment" else "inline", file.name),
        )
        if (partial) headers += "Content-Range" to "bytes $start-$end/$length"
        // Pages and images that can run script must not run on this site
        if (!download && (type.contains("html") || type.contains("xml") || type.contains("svg"))) {
            headers += "Content-Security-Policy" to "sandbox"
        }
        writeHead(out, if (partial) 206 else 200, headers, true)
        if (req.isHead || count <= 0) return true

        val transfer = if (download && start == 0L) FileTransfer.begin(file.name, Direction.FROM_PHONE, length, device.name) else null
        var ok = false
        try {
            FileInputStream(file).use { input ->
                if (start > 0) input.channel.position(start)
                copy(input, out, count) { n ->
                    FileTransfer.bytesSent.addAndGet(n.toLong())
                    transfer?.let { it.done += n }
                }
            }
            ok = true
        } finally {
            transfer?.let { FileTransfer.end(it, ok) }
        }
        return true
    }

    private fun thumb(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val (file, _) = resolve(device, req.param("path"))
        if (!file.isFile) throw HttpError(404, "File not found")
        val size = (req.param("size")?.toIntOrNull() ?: THUMB).coerceIn(64, MAX_PREVIEW)
        val key = "${file.path}|${file.lastModified()}|$size"
        var bytes = thumbs.get(key)
        if (bytes == null) {
            thumbSlots.acquire()
            bytes = try {
                makeThumb(file, size)
            } finally {
                thumbSlots.release()
            } ?: throw HttpError(404, "No preview")
            if (size <= THUMB) thumbs.put(key, bytes)
        }
        return sendBytes(out, req, 200, "image/jpeg", bytes, listOf("Cache-Control" to "private, max-age=604800"))
    }

    @Suppress("DEPRECATION")
    private fun makeThumb(file: File, size: Int): ByteArray? {
        val type = Names.mime(file.name)
        val bitmap: Bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val s = Size(size, size)
                when {
                    type.startsWith("image/") -> ThumbnailUtils.createImageThumbnail(file, s, null)
                    type.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(file, s, null)
                    type.startsWith("audio/") -> ThumbnailUtils.createAudioThumbnail(file, s, null)
                    else -> null
                }
            } else when {
                type.startsWith("image/") -> decodeSampled(file, size)
                type.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Images.Thumbnails.MINI_KIND)
                else -> null
            }
        }.getOrNull() ?: return null
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, if (size > THUMB) 88 else 80, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    /** Android 9: decode a smaller copy of the image, turned upright from its EXIF orientation. */
    private fun decodeSampled(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** Selected files and folders as one zip, streamed while it is built (no temp file). */
    private fun zip(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val form = if (req.method == "POST") parseForm(req.body.text(1024 * 1024)) else req.query
        val (dir, _) = resolve(device, form["path"]?.firstOrNull())
        if (!dir.isDirectory) throw HttpError(404, "Folder not found")
        val names = form["name"].orEmpty()
        val selected = if (names.isEmpty()) listOf(dir) else names.map { resolve(device, File(dir, cleanName(it)).path).first }
        val base = if (names.isEmpty()) dir.parentFile ?: dir else dir
        val zipName = (if (selected.size == 1) selected[0].name else dir.name.ifEmpty { "files" }) + ".zip"

        val files = selected.flatMap { item -> if (item.isDirectory) item.walkTopDown().toList() else listOf(item) }
        val total = files.filter { it.isFile }.sumOf { it.length() }
        writeHead(out, 200, listOf(
            "Content-Type" to "application/zip",
            "Content-Disposition" to disposition("attachment", zipName),
            "Cache-Control" to "no-store",
        ), keepAlive = false)
        if (req.isHead) return false

        val transfer = FileTransfer.begin(zipName, Direction.FROM_PHONE, total, device.name)
        var ok = false
        try {
            val zip = ZipOutputStream(out)
            // Photos and videos are already compressed: store them to keep the phone fast
            zip.setLevel(Deflater.NO_COMPRESSION)
            val prefix = base.path.length + 1
            files.forEach { f ->
                if (!f.canRead()) return@forEach
                val entryName = f.path.substring(prefix).let { if (f.isDirectory) "$it/" else it }
                if (f.isDirectory && !f.list().isNullOrEmpty()) return@forEach
                zip.putNextEntry(ZipEntry(entryName).apply { time = f.lastModified() })
                if (f.isFile) FileInputStream(f).use { input ->
                    copy(input, zip, Long.MAX_VALUE) { n ->
                        FileTransfer.bytesSent.addAndGet(n.toLong())
                        transfer.done += n
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
            out.flush()
            ok = true
        } finally {
            FileTransfer.end(transfer, ok)
        }
        return false
    }

    // ------------------------------------------------------------------
    // API: changes (uploads, new folder, rename, delete)
    // ------------------------------------------------------------------

    private fun upload(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val (dir, root) = resolve(device, req.param("path"))
        requireWritable(root)
        if (!dir.isDirectory) throw HttpError(404, "Folder not found")
        val length = req.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Missing file size")
        val relative = Names.cleanRelative(req.param("name")) ?: throw HttpError(400, "Invalid name")
        val requested = resolve(device, File(dir, relative).path).first
        val parent = requested.parentFile ?: throw HttpError(400, "Invalid name")
        if (!parent.isDirectory && !parent.mkdirs()) throw HttpError(403, "Can't create folder ${parent.name}")
        if (length > parent.usableSpace) throw HttpError(507, "Not enough free space on the phone")

        val target = Names.reserveUnique(requested)
        val temp = File(parent, ".${target.name}.part")
        val transfer = FileTransfer.begin(target.name, Direction.TO_PHONE, length, device.name)
        var ok = false
        try {
            var received = 0L
            FileOutputStream(temp).use { output ->
                copy(req.body, output, length) { n ->
                    received += n
                    transfer.done = received
                    FileTransfer.bytesReceived.addAndGet(n.toLong())
                }
            }
            if (received < length) throw IOException("Upload interrupted")
            if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
            ok = true
        } finally {
            if (!ok) temp.delete()
            Names.release(target)
            FileTransfer.end(transfer, ok)
        }
        scan(listOf(target.path))
        return ok(out, req, JSONObject().put("ok", true).put("name", target.name).put("path", target.path))
    }

    private fun mkdir(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val (dir, root) = resolve(device, req.param("path"))
        requireWritable(root)
        val created = File(dir, cleanName(req.param("name")))
        if (created.exists()) throw HttpError(409, "\"${created.name}\" already exists")
        if (!created.mkdir()) throw HttpError(403, "Can't create a folder here")
        return ok(out, req, JSONObject().put("ok", true).put("path", created.path))
    }

    private fun rename(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val (file, root) = resolve(device, req.param("path"))
        requireWritable(root)
        if (!file.exists()) throw HttpError(404, "Not found")
        if (file == root.dir) throw HttpError(403, "Can't rename a shared folder from here")
        val target = File(file.parentFile, cleanName(req.param("name")))
        if (target.exists()) throw HttpError(409, "\"${target.name}\" already exists")
        if (!file.renameTo(target)) throw HttpError(403, "Can't rename \"${file.name}\"")
        FileTransfer.log("${device.name} renamed ${file.name} to ${target.name}")
        scan(listOf(file.path, target.path))
        return ok(out, req, JSONObject().put("ok", true).put("path", target.path))
    }

    private fun delete(req: Request, out: OutputStream, device: Hub.Device): Boolean {
        val paths = runCatching { JSONObject(req.body.text(1024 * 1024)).getJSONArray("paths") }
            .getOrElse { throw HttpError(400, "Nothing to delete") }
        val failed = JSONArray()
        val removed = mutableListOf<String>()
        val names = mutableListOf<String>()
        for (i in 0 until paths.length()) {
            val (file, root) = resolve(device, paths.getString(i))
            requireWritable(root)
            if (file == root.dir) throw HttpError(403, "Can't delete a shared folder or storage volume")
            // Media files inside need their gallery entries removed too
            val contents = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.take(5000).map { it.path }.toList() else listOf(file.path)
            if (file.deleteRecursively()) {
                removed += contents
                names += file.name
            } else {
                failed.put(file.name)
            }
        }
        if (names.isNotEmpty()) {
            FileTransfer.log("${device.name} deleted ${if (names.size == 1) names[0] else "${names.size} items"}", warning = true)
        }
        scan(removed)
        return ok(out, req, JSONObject().put("ok", failed.length() == 0).put("failed", failed))
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, onChunk: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var left = limit
        while (left > 0 && running) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            left -= n
            onChunk(n)
        }
        if (!running) throw IOException("Server stopped")
    }
}
