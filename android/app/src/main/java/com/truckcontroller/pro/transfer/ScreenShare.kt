package com.truckcontroller.pro.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Phone screen → JPEG frames for [LiveShare.screen], through Android's screen capture
 * (the user approved it; Android shows its own capture indicator). Follows rotation by resizing
 * the capture. Apps that block screenshots (banking, DRM video) show as black.
 */
class ScreenShare(
    private val context: Context,
    private val projection: MediaProjection,
    private val quality: LiveShare.Quality,
    private val onStopped: () -> Unit,
) {
    private val source = LiveShare.screen
    private val thread = HandlerThread("screen-share").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var lastCopy = 0L
    private var lastPublish = 0L
    /** Latest screen picture (with row padding); a still screen sends no new images. */
    private var buffer: Bitmap? = null
    private var bufferWidth = 0
    private var bufferValid = false
    private val output = ByteArrayOutputStream()
    @Volatile private var stopped = false

    /** Original: the screen's own resolution, no scaling. */
    private val maxSide get() = if (quality.original) Int.MAX_VALUE else max(quality.width, quality.height)

    private val checkSize = object : Runnable {
        override fun run() {
            if (stopped) return
            val (w, h, d) = captureSize()
            if (w != width || h != height) resize(w, h, d)
            // Nothing changed on screen: resend the last picture so new viewers see it
            if (source.wanted && bufferValid && SystemClock.elapsedRealtime() - lastPublish > 1000) publishBuffer()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        // Android 14+ requires the callback before the capture starts
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopped) onStopped()
            }
        }, handler)
        val (w, h, d) = captureSize()
        width = w
        height = h
        dpi = d
        val r = newReader(w, h)
        reader = r
        display = projection.createVirtualDisplay(
            "PhoneDeck screen", w, h, d, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        source.start()
        handler.postDelayed(checkSize, 1000)
    }

    /** Screen size now (follows rotation), scaled so the long side fits the quality. */
    private fun captureSize(): Triple<Int, Int, Int> {
        // DisplayManager works from a service (WindowManager wants a visual context on Android 11+)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val realW = metrics.widthPixels
        val realH = metrics.heightPixels
        val density = metrics.densityDpi
        val scale = minOf(1f, maxSide.toFloat() / max(realW, realH))
        // Even sizes keep encoders and scalers happy
        val w = ((realW * scale).roundToInt() / 2) * 2
        val h = ((realH * scale).roundToInt() / 2) * 2
        return Triple(w, h, (density * scale).roundToInt().coerceAtLeast(120))
    }

    private fun newReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ onImage(it) }, handler)
    }

    private fun resize(w: Int, h: Int, d: Int) {
        val vd = display ?: return
        width = w
        height = h
        dpi = d
        val old = reader
        val r = newReader(w, h)
        reader = r
        vd.resize(w, h, d)
        vd.surface = r.surface
        old?.close()
        buffer = null
        bufferValid = false
    }

    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            // Copy at most at the stream frame rate; idle copies are rare enough to keep for late viewers
            if (now - lastCopy < 1000L / quality.screenFps) return
            lastCopy = now
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            var bmp = buffer
            if (bmp == null || bmp.width != paddedWidth || bmp.height != h) {
                bmp = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
                buffer = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            bufferWidth = w
            bufferValid = true
            if (source.wanted) publishBuffer()
        } catch (_: Exception) {
            // A bad frame is skipped
        } finally {
            image.close()
        }
    }

    private fun publishBuffer() {
        val bmp = buffer ?: return
        val w = bufferWidth
        val h = bmp.height
        val frame = if (bmp.width != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
        output.reset()
        frame.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, output)
        if (frame !== bmp) frame.recycle()
        lastPublish = SystemClock.elapsedRealtime()
        source.publish(output.toByteArray(), w, h)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacks(checkSize)
        handler.post {
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { projection.stop() }
            thread.quitSafely()
        }
        source.stop()
    }
}
