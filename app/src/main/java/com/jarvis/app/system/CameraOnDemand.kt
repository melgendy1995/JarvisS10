package com.jarvis.app.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jarvis.core.skills.CameraOutcome
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Verdict for an authenticated request that wants the camera feed. */
sealed interface MediaAccess {
    object Allow : MediaAccess

    data class Refuse(val reason: String) : MediaAccess
}

/**
 * Backing hooks for [CameraHttpServer]. The app-lifetime [CameraOnDemand]
 * implements it so the server stays a free-standing piece.
 */
internal interface CameraOnDemandHooks {
    /** Gate for /, /stream, /snapshot.jpg and POST /offer. May launch a start. */
    fun mediaAccess(): MediaAccess

    /** Named viewer for an open /stream connection; pair with [viewerClosed]. */
    fun streamViewerOpened(): String

    /** Snapshot-style viewer hit; counts fresh for the idle timeout only. */
    fun snapshotViewerTouched()

    fun viewerClosed(id: String)

    /** Live WebRTC peer count from the publisher. */
    fun peerCountChanged(count: Int)
}

/**
 * Always-on HTTP host plus camera auto-start for the on-demand feed. The server
 * binds at app start and retries while the port is busy. An authenticated media
 * request starts the camera unattended; once no viewers remain for the idle
 * window, only a camera this class auto-started is stopped again. Voice control
 * keeps the stock behaviour: a camera the user turned on never stops alone.
 */
internal class CameraOnDemand(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: FrameHub,
    private val password: () -> String,
    private val ports: CameraPortStore,
) : CameraOnDemandHooks {
    private val watch = ViewerIdleWatch(IDLE_MILLIS) { SystemClock.elapsedRealtime() }
    private val starting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val streamIds = AtomicInteger(0)

    @Volatile
    private var cooldownUntilMillis = 0L

    @Volatile
    private var autoStarted = false

    @Volatile
    private var hasPeers = false

    @Volatile
    private var server: CameraHttpServer? = null

    fun start() {
        CameraStreamService.onOutcome = { origin, outcome -> handleOutcome(origin, outcome) }
        scope.launch { bindingLoop() }
        scope.launch { monitorLoop() }
        Log.i(TAG, "always-on camera http host started")
    }

    /** A saved port change retires the bound server; the loop rebinds. */
    fun onPortChanged() {
        val current = server ?: return
        server = null
        runCatching { current.stop() }
        Log.i(TAG, "camera http host retired for the port change")
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        CameraStreamService.onOutcome = null
    }

    override fun mediaAccess(): MediaAccess {
        if (CameraStreamService.running) return MediaAccess.Allow
        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntilMillis) {
            return MediaAccess.Refuse("The phone is too hot; camera stays off for a while.")
        }
        launchStart()
        return MediaAccess.Allow
    }

    private fun launchStart() {
        if (!starting.compareAndSet(false, true)) return
        scope.launch {
            try {
                val outcome = CameraStreamService.start(
                    context,
                    CameraStreamService.lastLens,
                    CameraOrigin.ON_DEMAND,
                )
                handleOutcome(CameraOrigin.ON_DEMAND, outcome)
            } finally {
                starting.set(false)
            }
        }
    }

    private fun launchStop() {
        if (!stopping.compareAndSet(false, true)) return
        scope.launch {
            try {
                handleOutcome(
                    CameraOrigin.ON_DEMAND,
                    CameraStreamService.stop(context, CameraOrigin.ON_DEMAND),
                )
            } finally {
                stopping.set(false)
            }
        }
    }

    private fun handleOutcome(origin: CameraOrigin, outcome: CameraOutcome) {
        when {
            outcome == CameraOutcome.STARTED -> autoStarted = origin == CameraOrigin.ON_DEMAND
            outcome == CameraOutcome.THERMAL_STOP -> cooldownUntilMillis = SystemClock.elapsedRealtime() + COOLDOWN_MILLIS
            !CameraStreamService.running -> autoStarted = false
        }
    }

    override fun streamViewerOpened(): String {
        val id = "stream-" + streamIds.incrementAndGet()
        watch.arrived(id)
        return id
    }

    override fun snapshotViewerTouched() {
        watch.touched(SNAPSHOT_ID)
    }

    override fun viewerClosed(id: String) {
        watch.departed(id)
    }

    override fun peerCountChanged(count: Int) {
        val active = count > 0
        when {
            active && !hasPeers -> watch.arrived(WEBRTC_ID)
            !active && hasPeers -> watch.departed(WEBRTC_ID)
        }
        hasPeers = active
    }

    private suspend fun bindingLoop() {
        while (true) {
            val port = ports.get()
            val current = server
            if (current != null && current.listeningPort != port) {
                Log.i(TAG, "camera http port changed to $port")
                runCatching { current.stop() }
                server = null
            }
            if (server == null) {
                val http = CameraHttpServer(
                    context = context,
                    port = port,
                    password = password,
                    hub = hub,
                    answerer = { offer -> CameraStreamService.webrtcAnswer(offer) },
                    onDemand = this,
                )
                if (http.startSafely()) {
                    server = http
                } else {
                    Log.w(TAG, "camera http port $port busy, retrying")
                }
            }
            delay(RETRY_MILLIS)
        }
    }

    private suspend fun monitorLoop() {
        while (true) {
            if (!CameraStreamService.running) {
                autoStarted = false
            } else if (autoStarted && !starting.get()) {
                val idle = watch.idleMillis()
                if (idle != null && idle >= IDLE_MILLIS) launchStop()
            }
            delay(MONITOR_MILLIS)
        }
    }

    private companion object {
        const val TAG = "JarvisCamera"
        const val IDLE_MILLIS = 45_000L
        const val COOLDOWN_MILLIS = 90_000L
        const val RETRY_MILLIS = 5_000L
        const val MONITOR_MILLIS = 2_500L
        const val WEBRTC_ID = "webrtc"
        const val SNAPSHOT_ID = "snapshot"
    }
}
