package com.jarvis.app.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/** Ducks music for a wake turn. Recording itself does not request focus, so YouTube keeps playing. */
class MediaDuck(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    fun mediaPlaying(): Boolean {
        if (audio.isMusicActive) return true
        return audio.activePlaybackConfigurations.any { config ->
            config.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
                config.toString().contains("state:started")
        }
    }

    fun acquireIfPlaying() {
        if (request != null) return
        if (!mediaPlaying()) {
            Log.i(WakeLog.TAG, "duck skipped, nothing is playing")
            return
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                Log.i(WakeLog.TAG, "audio focus change $change")
            }
            .build()
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = focus
        Log.i(WakeLog.TAG, "duck granted=$granted")
    }

    fun release() {
        val focus = request ?: return
        request = null
        audio.abandonAudioFocusRequest(focus)
        Log.i(WakeLog.TAG, "duck released")
    }
}
