package com.jarvis.app.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.app.R
import com.jarvis.audio.wake.WakeHit
import com.jarvis.audio.wake.WakeListener
import com.jarvis.audio.wake.WakeSession
import com.jarvis.audio.wake.WavPcm
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on Hey Jarvis listener.
 * Android 12 only allows a microphone foreground service that was started
 * while the activity was visible. The recorder uses VOICE_RECOGNITION and
 * does not take audio focus, so a video in front keeps playing until a wake
 * ducks it.
 */
class ListenForegroundService : Service() {
    private var status = "Listening for Hey Jarvis"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(WakeLog.TAG, "microphone foreground service stopped")
                return START_NOT_STICKY
            }
            ACTION_NOTE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) status = text
            }
        }
        val posted = runCatching {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        if (posted.isFailure) {
            Log.w(WakeLog.TAG, "microphone foreground service rejected: ${posted.exceptionOrNull()?.javaClass?.simpleName}")
            running = false
            ready?.complete(Unit)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        ready?.complete(Unit)
        if (listener == null && hasMic()) {
            val created = WakeListener(this, WakeRuntime.session) { hit ->
                WakeRuntime.triggers.tryEmit(hit)
            }
            listener = created
            WakeRuntime.listener = created
            created.start()
            WakeRuntime.session.enter(WakeSession.Phase.PASSIVE)
            WakeLog.state("PASSIVE")
            Log.i(WakeLog.TAG, "microphone foreground service started")
        } else if (!hasMic()) {
            Log.w(WakeLog.TAG, "microphone permission is missing")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        listener?.close()
        listener = null
        if (WakeRuntime.listener != null) WakeRuntime.listener = null
        WakeLog.state("STOPPED")
    }

    private fun hasMic(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Jarvis is listening for Hey Jarvis"
                    setShowBadge(false)
                },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Jarvis")
            .setContentText(status)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "jarvis_listen"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.jarvis.app.LISTEN_STOP"
        private const val ACTION_NOTE = "com.jarvis.app.LISTEN_NOTE"
        private const val EXTRA_TEXT = "text"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var listener: WakeListener? = null

        @Volatile
        private var ready: CompletableDeferred<Unit>? = null

        /** Call while Jarvis is still visible. Android 12 rejects a later start from the background. */
        suspend fun startWhileVisible(context: Context) {
            if (running && listener != null) return
            val gate = CompletableDeferred<Unit>()
            ready = gate
            val intent = Intent(context, ListenForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
            withTimeoutOrNull(4_000) { gate.await() }
        }

        fun note(context: Context, text: String) {
            if (!running) return
            val intent = Intent(context, ListenForegroundService::class.java)
                .setAction(ACTION_NOTE)
                .putExtra(EXTRA_TEXT, text)
            runCatching { context.startService(intent) }
        }

        /**
         * Score a wav on the running listener. A background broadcast cannot
         * start a service on Android 12, so this talks to the in-process listener.
         */
        fun scoreWav(context: Context, path: String, emit: Boolean) {
            val active = listener
            val file = resolveWav(context, path)
            if (file == null || active == null) {
                Log.w(WakeLog.TAG, "debug wav missing file=$path listener=${active != null}")
                return
            }
            Thread({
                val clip = runCatching { WavPcm.read(file.readBytes()) }.getOrNull()
                if (clip == null) {
                    Log.w(WakeLog.TAG, "debug wav unreadable $path")
                    return@Thread
                }
                val score = active.scoreWav(clip.samples)
                if (emit && score.triggered) {
                    WakeRuntime.triggers.tryEmit(WakeHit(score.max, duringSpeech = false))
                    Log.i(WakeLog.TAG, "debug wav emitted trigger")
                }
            }, "jarvis-wake-wav").start()
        }

        private fun resolveWav(context: Context, path: String): File? {
            val direct = File(path)
            if (direct.isFile) return direct
            val internal = File(context.filesDir, path)
            if (internal.isFile) return internal
            val external = context.getExternalFilesDir(null)
            if (external != null) {
                val candidate = File(external, path)
                if (candidate.isFile) return candidate
            }
            return null
        }

        fun stop(context: Context) {
            if (!running) return
            val intent = Intent(context, ListenForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
