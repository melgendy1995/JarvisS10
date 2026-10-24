package com.jarvis.audio.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/** Short two-tone chime on the assistant stream so it ducks instead of pausing playback. */
class WakeChime(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun playAndWait() {
        val rate = WakeConfig.SAMPLE_RATE
        val count = (rate * 0.18f).toInt()
        val pcm = ShortArray(count)
        for (i in pcm.indices) {
            val t = i.toDouble() / rate
            val env = 1.0 - i.toDouble() / count
            val tone = sin(2 * PI * 880 * t) * 0.55 + sin(2 * PI * 1320 * t) * 0.28
            pcm[i] = (tone * env * 12_000).toInt().coerceIn(-32767, 32767).toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(count * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(240)
            Log.i(WakeConfig.LOG_TAG, "chime played")
        } catch (error: Exception) {
            Log.w(WakeConfig.LOG_TAG, "chime failed ${error.javaClass.simpleName}")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
