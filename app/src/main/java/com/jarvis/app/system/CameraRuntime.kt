package com.jarvis.app.system

import com.jarvis.core.camera.MotionSnapshotSink
import com.jarvis.core.camera.NoOpMotionSnapshotSink
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide camera state. The red indicator reads [streaming].
 * [motionSnapshots] is the later Telegram hook. The live pipeline does not call it.
 */
object CameraRuntime {
    val streaming = MutableStateFlow(false)

    @Volatile
    var facingFront: Boolean = true

    @Volatile
    var motionSnapshots: MotionSnapshotSink = NoOpMotionSnapshotSink

    fun offerMotionSnapshot(jpeg: ByteArray, capturedAtMillis: Long) {
        motionSnapshots.onMotionSnapshot(jpeg, capturedAtMillis)
    }
}
