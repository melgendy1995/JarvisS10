package com.jarvis.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Streams 24 kHz 16-bit mono PCM from Gemini Live. */
class PcmSpeaker(
    private val sampleRate: Int = 24_000,
) {
    private var track: AudioTrack? = null
    private var writtenFrames: Int = 0

    fun start(): Boolean {
        if (track != null) return true
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return false
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            return false
        }
        created.play()
        track = created
        writtenFrames = 0
        return true
    }

    /** @return the level of this chunk, or null if the track rejected it. */
    fun write(pcm: ByteArray): Float? {
        val current = track ?: return null
        var offset = 0
        while (offset < pcm.size) {
            val wrote = current.write(pcm, offset, pcm.size - offset)
            if (wrote <= 0) return null
            offset += wrote
        }
        writtenFrames += pcm.size / 2
        return PcmLevel.rms16(pcm)
    }

    /** Blocks until the written frames have played, or [timeoutMs] elapses. */
    fun drain(timeoutMs: Long) {
        val current = track ?: return
        val target = writtenFrames
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (current.playbackHeadPosition < target && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun stop() {
        val current = track
        track = null
        writtenFrames = 0
        if (current == null) return
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.stop() }
        current.release()
    }
}
