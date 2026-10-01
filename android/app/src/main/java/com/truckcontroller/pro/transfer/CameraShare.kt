package com.truckcontroller.pro.transfer

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Phone camera → JPEG frames for [LiveShare.camera]. Runs in the File Transfer service (its own
 * lifecycle, so it keeps going with the screen off). Create, start and control on the main thread.
 */
class CameraShare(
    private val context: Context,
    private val quality: LiveShare.Quality,
    private val onError: (String) -> Unit,
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val source = LiveShare.camera
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    /** Full-resolution photos for snapshots (null when the phone can't run it next to the stream). */
    private var capture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var lastEncode = 0L
    private val output = ByteArrayOutputStream()

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                onError("The camera isn't available")
                return@addListener
            }
            bind()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind() {
        val p = provider ?: return
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        p.unbindAll()
        val wanted = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val selector = if (runCatching { p.hasCamera(wanted) }.getOrDefault(false)) wanted else CameraSelector.DEFAULT_BACK_CAMERA
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(quality.width, quality.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            // Original: allow sizes above the usual stream limit (up to 4K), at a lower frame rate
            .apply { if (quality.original) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
            .build()
        val use = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Frames come out upright for the chosen rotation, so browsers need no rotating
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(surfaceRotation(LiveShare.rotation))
            .build()
        use.setAnalyzer(executor, ::encode)
        analysis = use
        val photo = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(surfaceRotation(LiveShare.rotation))
            .build()
        // Stream + full-size photo when the camera supports both together, else the stream alone
        camera = runCatching { p.bindToLifecycle(this, selector, use, photo) }
            .onSuccess { capture = photo }
            .recoverCatching {
                p.unbindAll()
                capture = null
                p.bindToLifecycle(this, selector, use)
            }
            .getOrElse {
                onError("Couldn't open the camera: ${it.message}")
                return
            }
        LiveShare.takePhoto = if (capture != null) ::takePhoto else null
        val info = camera?.cameraInfo
        LiveShare.lens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
        LiveShare.hasTorch = info?.hasFlashUnit() == true
        if (!LiveShare.hasTorch) LiveShare.torch = false
        camera?.cameraControl?.enableTorch(LiveShare.torch)
        source.start()
    }

    private fun encode(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!source.wanted || now - lastEncode < 1000L / quality.fps) return
            lastEncode = now
            val bitmap = image.toBitmap()
            output.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, output)
            source.publish(output.toByteArray(), bitmap.width, bitmap.height)
            bitmap.recycle()
        } catch (_: Exception) {
            // A bad frame is skipped
        } finally {
            image.close()
        }
    }

    /** A real photo at the camera's full resolution, as JPEG bytes (with EXIF, so viewers turn it upright). */
    private fun takePhoto(done: (ByteArray?) -> Unit) {
        val photo = capture ?: return done(null)
        val bytes = java.io.ByteArrayOutputStream()
        val options = ImageCapture.OutputFileOptions.Builder(bytes).build()
        photo.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) = done(bytes.toByteArray())
            override fun onError(e: ImageCaptureException) = done(null)
        })
    }

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        LiveShare.torch = false
        bind()
        LiveShare.changed()
    }

    fun toggleTorch() {
        if (!LiveShare.hasTorch) return
        LiveShare.torch = !LiveShare.torch
        camera?.cameraControl?.enableTorch(LiveShare.torch)
        LiveShare.changed()
    }

    fun rotate() {
        LiveShare.rotation = (LiveShare.rotation + 90) % 360
        analysis?.targetRotation = surfaceRotation(LiveShare.rotation)
        capture?.targetRotation = surfaceRotation(LiveShare.rotation)
        LiveShare.changed()
    }

    fun stop() {
        runCatching { camera?.cameraControl?.enableTorch(false) }
        LiveShare.torch = false
        LiveShare.takePhoto = null
        provider?.unbindAll()
        registry.currentState = Lifecycle.State.DESTROYED
        executor.shutdown()
        source.stop()
    }

    /** Degrees the picture is turned → the CameraX target rotation that produces it. */
    private fun surfaceRotation(degrees: Int) = when (degrees) {
        90 -> Surface.ROTATION_270
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}
