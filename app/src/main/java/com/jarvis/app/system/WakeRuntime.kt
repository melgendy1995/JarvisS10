package com.jarvis.app.system

import android.util.Log
import com.jarvis.audio.wake.WakeConfig
import com.jarvis.audio.wake.WakeHit
import com.jarvis.audio.wake.WakeListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow

/** Process-wide handle so the foreground service and the voice loop share one microphone. */
object WakeRuntime {
    val triggers = MutableSharedFlow<WakeHit>(extraBufferCapacity = 8)

    @Volatile
    var listener: WakeListener? = null

    fun pauseBlocking() {
        listener?.pauseBlocking()
    }

    fun resume(speaking: Boolean) {
        listener?.resume(speaking)
    }
}

object WakeLog {
    const val TAG = WakeConfig.LOG_TAG
    private var state = "STOPPED"
    var triggers: Int = 0
        private set
    var falseTriggers: Int = 0
        private set

    fun state(next: String) {
        if (next == state) return
        Log.i(TAG, "state $state -> $next")
        state = next
    }

    fun trigger(detail: String) {
        triggers++
        Log.i(TAG, "trigger $detail count=$triggers state=$state")
    }

    fun falseTrigger() {
        falseTriggers++
        Log.i(TAG, "falseTrigger count=$falseTriggers")
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun score(value: Float) {
        Log.i(TAG, "score hey_jarvis=${String.format(Locale.US, "%.3f", value)} state=$state")
    }
}
