package com.jarvis.app.system

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import kotlinx.coroutines.delay

/**
 * A spoken reply takes audio focus (Google TTS asks for transient focus), and YouTube
 * Music pauses on that loss and only resumes on its own window focus. Remember what
 * was playing when a turn starts and send play again through its session after the
 * reply, unless the user asked to pause or start something else in that turn.
 */
object MusicGuard {
    private const val TAG = "JarvisMusicGuard"

    @Volatile private var remembered: Set<String> = emptySet()

    fun remember(context: Context, reason: String) {
        val playing = sessions(context)
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.packageName }
            .toSet()
        if (playing.isEmpty()) return
        remembered = remembered + playing
        Log.i(TAG, "remember playing=$playing reason=$reason")
    }

    fun forget(reason: String) {
        if (remembered.isEmpty()) return
        remembered = emptySet()
        Log.i(TAG, "forget reason=$reason")
    }

    /** Call after the reply finished. Retries for a few seconds; focus comes back late. */
    suspend fun restore(context: Context) {
        val targets = remembered
        if (targets.isEmpty()) return
        remembered = emptySet()
        repeat(10) { attempt ->
            delay(500)
            val pending = sessions(context).filter { it.packageName in targets }
            val paused = pending.filter { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            if (paused.isEmpty()) {
                if (attempt > 0) Log.i(TAG, "restore done targets=$targets")
                return
            }
            for (controller in paused) {
                runCatching { controller.transportControls.play() }
                Log.i(TAG, "restore play pkg=${controller.packageName} attempt=$attempt")
            }
        }
        Log.i(TAG, "restore gave up targets=$targets")
    }

    private fun sessions(context: Context) = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        manager.getActiveSessions(ComponentName(context, JarvisMediaListener::class.java))
    } catch (_: SecurityException) {
        emptyList()
    }
}
