package com.truckcontroller.pro.transfer

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live view: the phone's camera or screen as a stream of JPEG frames that connected browsers
 * watch (MJPEG). Sharing is started on the phone only; frames are encoded only while someone
 * watches (or asks for a snapshot), so an idle share costs little battery.
 */
object LiveShare {

    /**
     * Picture size and JPEG quality. [ORIGINAL]: the screen at its own resolution and the camera at
     * up to 4K (its best video size), near-lossless; fewer frames per second and much more data.
     */
    enum class Quality(val title: String, val width: Int, val height: Int, val jpeg: Int, val fps: Int, val screenFps: Int) {
        LOW("Low", 640, 480, 60, 10, 10),
        MEDIUM("Medium", 1280, 720, 70, 15, 15),
        HIGH("High", 1920, 1080, 80, 15, 15),
        ORIGINAL("Original", 3840, 2160, 92, 10, 15);

        val original get() = this == ORIGINAL
    }

    private const val KEY_QUALITY = "live_quality"

    val camera = LiveSource("camera")
    val screen = LiveSource("screen")

    /** Camera state, for the browser's controls. */
    @Volatile var lens = "back"
    @Volatile var torch = false
    @Volatile var hasTorch = false
    /** Picture rotation in degrees (0, 90, 180, 270). */
    @Volatile var rotation = 0

    /** Takes a full-resolution camera photo (JPEG with EXIF) and calls back with it, or null; set by the camera. */
    @Volatile var takePhoto: (((ByteArray?) -> Unit) -> Unit)? = null

    /** Camera / screen actions from a browser ("lens", "torch", "rotate"); set by the service. */
    @Volatile var control: ((source: String, action: String) -> Unit)? = null
    /** Sharing or viewers changed; set by the service (pushes the status to browsers). */
    @Volatile var onChanged: (() -> Unit)? = null

    fun source(id: String?) = when (id) {
        "camera" -> camera
        "screen" -> screen
        else -> null
    }

    fun quality(context: Context): Quality =
        runCatching { Quality.valueOf(FileTransfer.prefs(context).getString(KEY_QUALITY, null)!!) }.getOrDefault(Quality.MEDIUM)

    fun setQuality(context: Context, quality: Quality) =
        FileTransfer.prefs(context).edit().putString(KEY_QUALITY, quality.name).apply()

    internal fun changed() = onChanged?.invoke()

    fun statusJson(): JSONObject = JSONObject()
        .put("type", "live")
        .put("camera", camera.json().put("lens", lens).put("torch", torch).put("hasTorch", hasTorch).put("rotation", rotation))
        .put("screen", screen.json())

    val sharing get() = camera.on || screen.on
}

/** One shared picture source with its latest frame and the browsers watching it. */
class LiveSource(val id: String) {

    @Volatile var on = false
        internal set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    private val lock = Object()
    @Volatile private var frame: ByteArray? = null
    @Volatile private var seq = 0L
    private var lastAt = 0L

    private val viewers = ConcurrentHashMap<Int, String>()
    private val ids = AtomicInteger()
    private val snapshotWaiters = AtomicInteger()

    /** Encode frames only when somebody needs them. */
    val wanted get() = on && (viewers.isNotEmpty() || snapshotWaiters.get() > 0)

    fun viewerNames(): List<String> = viewers.values.distinct()

    val viewerCount get() = viewers.size

    internal fun start() {
        on = true
        LiveShare.changed()
    }

    internal fun stop() {
        on = false
        synchronized(lock) {
            frame = null
            lock.notifyAll()
        }
        LiveShare.changed()
    }

    internal fun publish(jpeg: ByteArray, w: Int, h: Int) {
        val sizeChanged = w != width || h != height
        width = w
        height = h
        synchronized(lock) {
            frame = jpeg
            seq++
            lastAt = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
        if (sizeChanged) LiveShare.changed()
    }

    /** Waits for a frame newer than [afterSeq]; returns (seq, frame) or null on timeout / stop. */
    internal fun await(afterSeq: Long, timeoutMs: Long): Pair<Long, ByteArray>? = synchronized(lock) {
        if (seq <= afterSeq && on) lock.wait(timeoutMs)
        val f = frame
        if (seq > afterSeq && f != null) seq to f else null
    }

    /** A fresh frame for a snapshot (waits up to [timeoutMs] while the producer wakes up). */
    internal fun snapshot(timeoutMs: Long): ByteArray? {
        synchronized(lock) {
            val f = frame
            if (f != null && SystemClock.elapsedRealtime() - lastAt < 1_000) return f
        }
        snapshotWaiters.incrementAndGet()
        try {
            return await(seq, timeoutMs)?.second
        } finally {
            snapshotWaiters.decrementAndGet()
        }
    }

    internal fun addViewer(name: String): Int {
        val viewer = ids.incrementAndGet()
        val first = viewers.values.none { it == name }
        viewers[viewer] = name
        if (first) FileTransfer.log("$name started watching the $id")
        LiveShare.changed()
        return viewer
    }

    internal fun removeViewer(viewer: Int) {
        val name = viewers.remove(viewer) ?: return
        if (viewers.values.none { it == name }) FileTransfer.log("$name stopped watching the $id")
        LiveShare.changed()
    }

    internal fun json(): JSONObject {
        val names = JSONArray()
        viewerNames().forEach { names.put(it) }
        return JSONObject().put("on", on).put("width", width).put("height", height)
            .put("viewers", viewers.size).put("names", names)
    }
}
