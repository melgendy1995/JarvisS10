package com.jarvis.orb

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper

/**
 * Full-screen point cloud. Render-when-dirty so idle stays near 15 fps instead of vsync.
 * The surface stays behind the window; Compose chrome draws on top of the hole.
 */
internal class OrbGLSurfaceView(
    context: Context,
    private val orbRenderer: OrbRenderer,
) : GLSurfaceView(context) {
    private val main = Handler(Looper.getMainLooper())
    private var looping = false
    private val tick = object : Runnable {
        override fun run() {
            if (!looping) return
            requestRender()
            main.postDelayed(this, orbRenderer.frameDelayMs)
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        holder.setFormat(PixelFormat.OPAQUE)
        setRenderer(orbRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun resumeRendering() {
        if (looping) return
        looping = true
        onResume()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun pauseRendering() {
        if (!looping) return
        looping = false
        main.removeCallbacks(tick)
        onPause()
    }

    override fun onDetachedFromWindow() {
        pauseRendering()
        super.onDetachedFromWindow()
    }
}
