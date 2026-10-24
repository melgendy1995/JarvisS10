package com.jarvis.app.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the process in the foreground while another app covers Jarvis.
 * Android 12 only allows the microphone in the background from a foreground
 * service of type microphone that was started while Jarvis was visible.
 * This service does not open the microphone: a capture would take audio
 * focus and pause the player we just started.
 */
class ListenForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "microphone foreground service stopped")
            return START_NOT_STICKY
        }
        val posted = runCatching {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        if (posted.isFailure) {
            Log.w(TAG, "microphone foreground service rejected: ${posted.exceptionOrNull()?.javaClass?.simpleName}")
            running = false
            ready?.complete(Unit)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        ready?.complete(Unit)
        Log.i(TAG, "microphone foreground service started")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Jarvis is behind another app"
                    setShowBadge(false)
                },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Jarvis")
            .setContentText("Available behind another app. The microphone stays off during playback.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "JarvisAppControl"
        private const val CHANNEL = "jarvis_listen"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.jarvis.app.LISTEN_STOP"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var ready: CompletableDeferred<Unit>? = null

        /** Call while Jarvis is still visible. Android 12 rejects a later start from the background. */
        suspend fun startWhileVisible(context: Context) {
            if (running) return
            val gate = CompletableDeferred<Unit>()
            ready = gate
            val intent = Intent(context, ListenForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
            withTimeoutOrNull(4_000) { gate.await() }
        }

        fun stop(context: Context) {
            if (!running) return
            val intent = Intent(context, ListenForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
