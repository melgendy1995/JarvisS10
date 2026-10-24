package com.jarvis.core.camera

/**
 * Later hook for a Telegram motion alert. Nothing calls this yet.
 * A detector should pass one JPEG, not a stream, and must not log the bytes.
 */
fun interface MotionSnapshotSink {
    fun onMotionSnapshot(jpeg: ByteArray, capturedAtMillis: Long)
}

object NoOpMotionSnapshotSink : MotionSnapshotSink {
    override fun onMotionSnapshot(jpeg: ByteArray, capturedAtMillis: Long) = Unit
}
