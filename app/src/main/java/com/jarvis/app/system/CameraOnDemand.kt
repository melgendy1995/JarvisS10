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
 * Pure stop policy for the auto-started camera. Only an authenticated viewer
 * request (the HTTP server checks the password before [MediaAccess] is ever
 * asked) or a voice "camera on" starts or extends the camera; and the camera
 * the class started itself stops again on its own, either DEPART_GRACE_MILLIS
 * after the last sticky viewer left (nothing else joined the shelf), or after
 * the idle timeout for a snapshot-style audience.
 */
internal object CameraStopPolicy {
    const val REASON_IDLE = "idle"
    const val REASON_NO_VIEWERS = "no-viewers"

    /**
     * Why the auto-started camera should stop now, or null to keep running.
     * [departGraceExpired] means the last sticky viewer (WebRTC or /stream)
     * departed and the reconnect grace is spent with an empty shelf: stop on
     * the grace alone, not with the idle timeout stacked on top of it.
     */
    fun stopReason(
        running: Boolean,
        autoStarted: Boolean,
        starting: Boolean,
        departGraceExpired: Boolean,
        idleMillis: Long?,
        idleTimeoutMillis: Long,
    ): String? {
        if (!running || !autoStarted || starting) return null
        if (departGraceExpired && idleMillis != null) return REASON_NO_VIEWERS
        if (idleMillis != null && idleMillis >= idleTimeoutMillis) return REASON_IDLE
        return null
    }
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
 * request starts the camera unattended; once the shelf is empty — the depart
 * grace after the last sticky viewer, or the idle window for a snapshot
 * audience — only a camera this class auto-started is stopped again, with the
 * reason logged. Voice control keeps the stock behaviour: a camera the user
 * turned on never stops alone.
 */
internal class CameraOnDemand(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: FrameHub,
    private val password: () -> String,
    private val ports: CameraPortStore,
) : CameraOnDemandHooks {
    private val watch = ViewerIdleWatch(
        idleTimeoutMillis = IDLE_MILLIS,
        nowMillis = { SystemClock.elapsedRealtime() },
        // A WebRTC peer dropped during a transient ICE DISCONNECTED gets this
        // window to reconnect before it counts as the shelf going empty.
        departGraceMillis = DEPART_GRACE_MILLIS,
    )
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

    /** Read-only, for CameraStatusSkill: viewers on the shelf right now. */
    fun viewerCount(): Int = watch.activeCount()

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
                    port = port,
                    viewerHtml = { viewerResource() },
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
            val running = CameraStreamService.running
            if (!running) {
                autoStarted = false
            } else if (autoStarted && !starting.get()) {
                val reason = CameraStopPolicy.stopReason(
                    running = true,
                    autoStarted = true,
                    starting = false,
                    departGraceExpired = watch.departGraceExpired(),
                    idleMillis = watch.idleMillis(),
                    idleTimeoutMillis = IDLE_MILLIS,
                )
                if (reason != null) {
                    Log.i(TAG, "camera stop reason=$reason")
                    launchStop()
                }
            }
            delay(MONITOR_MILLIS)
        }
    }

    private fun viewerResource(): String {
        return context.resources.openRawResource(com.jarvis.app.R.raw.camera_viewer)
            .bufferedReader()
            .use { it.readText() }
    }

    private companion object {
        const val TAG = "JarvisCamera"
        const val IDLE_MILLIS = 45_000L
        const val COOLDOWN_MILLIS = 90_000L
        const val RETRY_MILLIS = 5_000L
        const val MONITOR_MILLIS = 2_500L
        const val WEBRTC_ID = "webrtc"
        const val SNAPSHOT_ID = "snapshot"

        /** Breath after the last WebRTC peer drops, matching the 1–8 s viewer backoff. */
        const val DEPART_GRACE_MILLIS = 15_000L
    }
}
