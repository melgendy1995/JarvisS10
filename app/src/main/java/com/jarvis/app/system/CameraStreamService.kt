package com.jarvis.app.system

import android.Manifest
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.jarvis.app.JarvisApplication
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import com.jarvis.core.camera.CameraProfile
import com.jarvis.core.camera.CameraProfiles
import com.jarvis.core.camera.CameraThermal
import com.jarvis.core.camera.ThermalTier
import com.jarvis.core.skills.CameraController
import com.jarvis.core.skills.CameraLens
import com.jarvis.core.skills.CameraOutcome
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Camera foreground service, separate from the microphone service so Hey Jarvis
 * keeps the mic. Video only. It does not start an activity or leave lock task.
 *
 * Android 12 rejects a camera foreground start from the background. Callers
 * start this while Jarvis is visible. Android 14's while-in-use rule is the
 * same idea: the activity must be in front, unless a documented exemption applies.
 * The device here is Android 12, and the manifest still declares the camera
 * foreground-service type required by the app's target SDK.
 */
class CameraStreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hub = FrameHub()
    private var engine: CameraEngine? = null
    private var publisher: WebRtcPublisher? = null
    private var server: CameraHttpServer? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val closed = AtomicBoolean(false)
    private var profile = CameraProfiles.FULL
    private var requestedLens = CameraLens.DEFAULT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            shutdown(thermal = false)
            finish(CameraOutcome.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promote()) {
            finish(CameraOutcome.FAILED)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_START) {
            requestedLens = CameraLens.fromName(intent?.getStringExtra(EXTRA_LENS)) ?: CameraLens.DEFAULT
        }
        when (action) {
            ACTION_SWITCH -> scope.launch { doSwitch() }
            else -> scope.launch { doStart() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown(thermal = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(): Boolean {
        return runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification("Camera streaming"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        }.onFailure { error ->
            Log.w(TAG, "foreground rejected: ${error.javaClass.simpleName}")
            if (error is ForegroundServiceStartNotAllowedException) finish(CameraOutcome.NOT_IN_FOREGROUND)
            else if (error is SecurityException) finish(CameraOutcome.NEEDS_PERMISSION)
        }.isSuccess
    }

    private suspend fun doStart() {
        if (running) {
            finish(CameraOutcome.ALREADY_ON)
            return
        }
        closed.set(false)
        hub.reopen()
        running = true
        val passwords = (applicationContext as? JarvisApplication)?.cameraPasswords
        if (passwords == null || passwords.get().isEmpty()) {
            Log.i(TAG, "server refused: no password")
            fail(CameraOutcome.NO_PASSWORD)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail(CameraOutcome.NEEDS_PERMISSION)
            return
        }
        noteThermalConstants()
        val power = getSystemService(PowerManager::class.java)
        val status = power?.currentThermalStatus ?: CameraThermal.NONE
        Log.i(TAG, "thermal status=$status tier=${CameraThermal.tier(status)}")
        if (CameraThermal.tier(status) == ThermalTier.STOP) {
            fail(CameraOutcome.THERMAL_STOP)
            return
        }
        profile = CameraProfiles.forTier(CameraThermal.tier(status)) ?: CameraProfiles.FULL
        registerThermal(power)
        if (closed.get()) return
        val capture = WebRtcPublisher(this)
        publisher = capture
        val webrtcOk = withContext(Dispatchers.Default) { capture.awaitReady(4_000) }
        if (closed.get()) return
        if (webrtcOk) capture.adapt(profile)
        val session = CameraEngine(this, hub) { degrees -> capture.setRotation(degrees) }
        engine = session
        session.setThermalStatus(status)
        val provider = runCatching { awaitProvider() }.getOrElse { error ->
            Log.w(TAG, "camera provider failed: ${error.javaClass.simpleName}")
            if (!closed.get()) fail(if (error is SecurityException) CameraOutcome.NEEDS_PERMISSION else CameraOutcome.FAILED)
            return
        }
        if (closed.get()) return
        val bound = runCatching {
            session.open(provider, if (webrtcOk) capture.surfaceProvider else null, profile, front = requestedLens == CameraLens.FRONT)
        }.onFailure { error ->
            Log.w(TAG, "camera bind failed: ${error.javaClass.simpleName}")
        }.isSuccess
        if (!bound) {
            if (!closed.get()) fail(CameraOutcome.FAILED)
            return
        }
        val gotFrame = withContext(Dispatchers.Default) { hub.awaitFirst(6_000) }
        if (closed.get()) return
        if (!gotFrame) {
            Log.w(TAG, "no camera frame")
            fail(CameraOutcome.FAILED)
            return
        }
        val port = CameraPortStore(this).get()
        val http = CameraHttpServer(
            context = this,
            port = port,
            password = { passwords.get() },
            hub = hub,
            answerer = { offer -> if (webrtcOk) capture.answer(offer) else null },
        )
        val listening = withContext(Dispatchers.IO) { http.startSafely() }
        if (closed.get()) {
            runCatching { http.stop() }
            return
        }
        if (!listening) {
            fail(CameraOutcome.FAILED)
            return
        }
        server = http
        running = true
        CameraRuntime.streaming.value = true
        val lock = getSystemService(ActivityManager::class.java)?.lockTaskModeState
        Log.i(TAG, "streaming port=$port webrtc=$webrtcOk micService=${ListenForegroundService.running} lockTask=$lock")
        finish(CameraOutcome.STARTED)
    }

    private fun doSwitch() {
        val session = engine
        if (!running || session == null) {
            finish(CameraOutcome.ALREADY_OFF)
            return
        }
        val switched = runCatching { session.switch() }.getOrDefault(false)
        if (!switched) {
            finish(CameraOutcome.FAILED)
            return
        }
        finish(if (CameraRuntime.facingFront) CameraOutcome.SWITCHED_FRONT else CameraOutcome.SWITCHED_BACK)
    }

    private fun registerThermal(power: PowerManager?) {
        if (power == null || thermalListener != null) return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            val tier = CameraThermal.tier(status)
            Log.i(TAG, "thermal status=$status tier=$tier")
            engine?.setThermalStatus(status)
            when (tier) {
                ThermalTier.STOP -> {
                    shutdown(thermal = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ThermalTier.REDUCED -> rebind(CameraProfiles.REDUCED)
                ThermalTier.FULL -> rebind(CameraProfiles.FULL)
            }
        }
        thermalListener = listener
        power.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
    }

    private fun rebind(next: CameraProfile) {
        if (next == profile) return
        profile = next
        publisher?.adapt(next)
        runCatching { engine?.apply(next) }
    }

    private fun fail(outcome: CameraOutcome) {
        shutdown(thermal = false)
        finish(outcome)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown(thermal: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        running = false
        CameraRuntime.streaming.value = false
        val power = getSystemService(PowerManager::class.java)
        val listener = thermalListener
        if (power != null && listener != null) {
            runCatching { power.removeThermalStatusListener(listener) }
        }
        thermalListener = null
        runCatching { server?.stop() }
        server = null
        hub.close()
        runCatching { engine?.close() }
        engine = null
        publisher?.dispose()
        publisher = null
        Log.i(TAG, if (thermal) "thermal severe, streaming stopped" else "camera service stopped")
        if (thermal) {
            val app = applicationContext as? JarvisApplication
            app?.announcements?.tryEmit("The phone is too hot, so I stopped the camera.")
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider {
        val future = ProcessCameraProvider.getInstance(this)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    cont.resumeWith(Result.success(future.get()))
                } catch (error: Throwable) {
                    cont.resumeWith(Result.failure(error))
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis camera", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the camera stream is on"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun noteThermalConstants() {
        if (PowerManager.THERMAL_STATUS_MODERATE != CameraThermal.MODERATE ||
            PowerManager.THERMAL_STATUS_SEVERE != CameraThermal.SEVERE
        ) {
            Log.e(
                TAG,
                "unexpected thermal constants moderate=${PowerManager.THERMAL_STATUS_MODERATE} severe=${PowerManager.THERMAL_STATUS_SEVERE}",
            )
        }
    }

    companion object {
        private const val TAG = "JarvisCamera"
        private const val CHANNEL = "jarvis_camera"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_START = "com.jarvis.app.CAMERA_START"
        private const val ACTION_STOP = "com.jarvis.app.CAMERA_STOP"
        private const val ACTION_SWITCH = "com.jarvis.app.CAMERA_SWITCH"
        private const val EXTRA_LENS = "lens"

        @Volatile
        var running: Boolean = false
            private set

        private val gateLock = Any()
        private var gate: CompletableDeferred<CameraOutcome>? = null

        suspend fun start(context: Context, lens: CameraLens = CameraLens.DEFAULT): CameraOutcome {
            val app = context.applicationContext
            val jarvis = app as? JarvisApplication
            if (jarvis == null || jarvis.cameraPasswords.get().isEmpty()) {
                Log.i(TAG, "server refused: no password")
                return CameraOutcome.NO_PASSWORD
            }
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return CameraOutcome.NEEDS_PERMISSION
            }
            val status = app.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: CameraThermal.NONE
            Log.i(TAG, "thermal status=$status tier=${CameraThermal.tier(status)}")
            if (CameraThermal.tier(status) == ThermalTier.STOP) return CameraOutcome.THERMAL_STOP
            if (running) return CameraOutcome.ALREADY_ON
            return request(app, ACTION_START) { it.putExtra(EXTRA_LENS, lens.name.lowercase()) }
        }

        suspend fun stop(context: Context): CameraOutcome {
            if (!running) return CameraOutcome.ALREADY_OFF
            return request(context.applicationContext, ACTION_STOP)
        }

        suspend fun switch(context: Context): CameraOutcome {
            if (!running) return CameraOutcome.ALREADY_OFF
            return request(context.applicationContext, ACTION_SWITCH)
        }

        private suspend fun request(context: Context, action: String, extras: (Intent) -> Unit = {}): CameraOutcome {
            val deferred = CompletableDeferred<CameraOutcome>()
            val previous = synchronized(gateLock) {
                val old = gate
                gate = deferred
                old
            }
            previous?.complete(CameraOutcome.FAILED)
            val intent = Intent(context, CameraStreamService::class.java).setAction(action).also(extras)
            val started = runCatching {
                if (action == ACTION_START) ContextCompat.startForegroundService(context, intent)
                else context.startService(intent)
            }
            if (started.isFailure) {
                synchronized(gateLock) { if (gate === deferred) gate = null }
                return mapStartError(started.exceptionOrNull())
            }
            return withTimeoutOrNull(20_000) { deferred.await() } ?: CameraOutcome.FAILED
        }

        fun finish(outcome: CameraOutcome) {
            val pending = synchronized(gateLock) { gate.also { gate = null } }
            pending?.complete(outcome)
        }

        private fun mapStartError(error: Throwable?): CameraOutcome {
            Log.w(TAG, "camera service rejected: ${error?.javaClass?.simpleName}")
            return when (error) {
                is ForegroundServiceStartNotAllowedException -> CameraOutcome.NOT_IN_FOREGROUND
                is SecurityException -> CameraOutcome.NEEDS_PERMISSION
                else -> CameraOutcome.FAILED
            }
        }
    }
}

class AndroidCameraController(context: Context) : CameraController {
    private val app = context.applicationContext

    override suspend fun turnOn(lens: CameraLens): CameraOutcome = CameraStreamService.start(app, lens)

    override suspend fun turnOff(): CameraOutcome = CameraStreamService.stop(app)

    override suspend fun switchCamera(): CameraOutcome = CameraStreamService.switch(app)
}
