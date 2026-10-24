package com.jarvis.app.system

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

internal object JpegFrames {
    fun from(image: ImageProxy, quality: Int): ByteArray {
        val bitmap = image.toBitmap()
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}

internal class FrameHub {
    private val lock = Object()
    private var jpeg: ByteArray? = null
    private var version = 0L
    private var closed = false

    fun publish(frame: ByteArray) {
        synchronized(lock) {
            if (closed) return
            jpeg = frame
            version += 1
            lock.notifyAll()
        }
    }

    fun snapshot(): ByteArray? = synchronized(lock) { jpeg }

    fun awaitFirst(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (jpeg == null && !closed) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000L
                if (leftMs <= 0) return false
                lock.wait(leftMs)
            }
            return jpeg != null
        }
    }

    /** Blocks until a frame newer than [versionSeen] or the wait elapses. Null if closed. */
    fun awaitAfter(versionSeen: Long, waitMs: Long): HeldFrame? {
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        synchronized(lock) {
            while (!closed && version <= versionSeen) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000L
                if (leftMs <= 0) return null
                lock.wait(leftMs)
            }
            val frame = jpeg ?: return null
            if (closed) return null
            return HeldFrame(version, frame)
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }

    fun reopen() {
        synchronized(lock) {
            closed = false
            jpeg = null
            version = 0
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            jpeg = null
            lock.notifyAll()
        }
    }
}

internal data class HeldFrame(val version: Long, val jpeg: ByteArray)
