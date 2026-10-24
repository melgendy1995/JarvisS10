package com.jarvis.app.system

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jarvis.core.camera.CameraProfile
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * One CameraX session. Preview feeds WebRTC. ImageAnalysis feeds JPEG snapshots and MJPEG.
 * Does not open the microphone.
 */
internal class CameraEngine(
    context: Context,
    private val hub: FrameHub,
    private val onRotation: (Int) -> Unit,
) {
    private val app = context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvisCameraAnalysis")
    }
    private val owner = CameraLifecycle()
    private var provider: ProcessCameraProvider? = null
    private var profile: CameraProfile? = null
    private var front = false
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private val thermalLabel = AtomicInteger(0)

    fun setThermalStatus(status: Int) {
        thermalLabel.set(status)
    }

    fun open(provider: ProcessCameraProvider, preview: Preview.SurfaceProvider?, profile: CameraProfile, front: Boolean) {
        this.provider = provider
        this.surfaceProvider = preview
        owner.resume()
        bind(profile, front)
    }

    fun apply(profile: CameraProfile) {
        val current = this.profile
        if (current == profile) return
        if (provider == null) return
        Log.i(TAG, "profile ${profile.width}x${profile.height} fps=${profile.fpsMin}-${profile.fpsMax}")
        bind(profile, front)
    }

    fun switch(): Boolean {
        val next = !front
        val current = provider ?: return false
        val selector = selector(next)
        if (!current.hasCamera(selector)) {
            Log.w(TAG, "facing unavailable front=$next")
            return false
        }
        val active = profile ?: return false
        bind(active, next)
        return true
    }

    fun close() {
        runCatching { provider?.unbindAll() }
        provider = null
        owner.destroy()
        analysisExecutor.shutdownNow()
    }

    private fun bind(profile: CameraProfile, front: Boolean) {
        val current = provider ?: return
        this.profile = profile
        this.front = front
        val selector = selector(front)
        if (!current.hasCamera(selector)) {
            throw IllegalStateException("camera missing")
        }
        current.unbindAll()
        val rotation = displayRotation()
        val preview = buildPreview(profile, rotation)
        val analysis = buildAnalysis(profile, rotation)
        val camera = if (preview != null) {
            current.bindToLifecycle(owner, selector, preview, analysis)
        } else {
            current.bindToLifecycle(owner, selector, analysis)
        }
        val degrees = camera.cameraInfo.getSensorRotationDegrees(rotation)
        onRotation(degrees)
        CameraRuntime.facingFront = front
        Log.i(
            TAG,
            "bound front=$front ${profile.width}x${profile.height} fps=${profile.fpsMin}-${profile.fpsMax} rotation=$degrees",
        )
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun buildPreview(profile: CameraProfile, rotation: Int): Preview? {
        val provider = surfaceProvider ?: return null
        val builder = Preview.Builder()
            .setResolutionSelector(selector(profile))
            .setTargetFrameRate(Range(profile.fpsMin, profile.fpsMax))
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(profile.fpsMin, profile.fpsMax),
        )
        return builder.build().also {
            it.targetRotation = rotation
            it.setSurfaceProvider(main, provider)
        }
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun buildAnalysis(profile: CameraProfile, rotation: Int): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(selector(profile))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setOutputImageRotationEnabled(true)
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(profile.fpsMin, profile.fpsMax),
        )
        val analysis = builder.build()
        analysis.targetRotation = rotation
        analysis.setAnalyzer(analysisExecutor, Analyzer(profile))
        return analysis
    }

    private fun selector(profile: CameraProfile): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(profile.width, profile.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
    }

    private fun selector(front: Boolean): CameraSelector {
        return if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun displayRotation(): Int {
        val manager = app.getSystemService(DisplayManager::class.java) ?: return Surface.ROTATION_90
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return Surface.ROTATION_90
        return display.rotation
    }

    private inner class Analyzer(private val profile: CameraProfile) : ImageAnalysis.Analyzer {
        private var lastPublishNs = 0L
        private var windowStartNs = System.nanoTime()
        private var frames = 0

        override fun analyze(image: androidx.camera.core.ImageProxy) {
            try {
                val now = System.nanoTime()
                val minGap = 1_000_000_000L / profile.fpsMax.coerceAtLeast(1)
                if (now - lastPublishNs >= minGap) {
                    val jpeg = JpegFrames.from(image, profile.jpegQuality)
                    if (jpeg.size > 32 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
                        lastPublishNs = now
                        hub.publish(jpeg)
                        frames++
                    }
                }
                if (now - windowStartNs >= 5_000_000_000L) {
                    val fps = frames * 1_000_000_000.0 / (now - windowStartNs)
                    Log.i(
                        TAG,
                        "capture fps=${String.format(Locale.US, "%.1f", fps)} ${image.width}x${image.height} thermal=${thermalLabel.get()}",
                    )
                    frames = 0
                    windowStartNs = now
                }
            } catch (error: Throwable) {
                Log.w(TAG, "frame dropped: ${error.javaClass.simpleName}")
            } finally {
                image.close()
            }
        }
    }

    private class CameraLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun resume() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "JarvisCamera"
    }
}
