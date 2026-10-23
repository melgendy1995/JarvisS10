package com.jarvis.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler

/**
 * 16 kHz 16-bit mono capture for Gemini Live.
 * [muted] drops frames so the uplink stays quiet while the assistant speaks.
 * The recorder keeps running so acoustic echo cancellation stays armed.
 */
class PcmMic(
    private val sampleRate: Int = 16_000,
) {
    @Volatile var muted: Boolean = false

    private val lock = Any()
    private var record: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start(onFrame: (pcm: ByteArray, level: Float) -> Unit): Boolean {
        stop()
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return false
        val buffer = maxOf(min, sampleRate / 5 * 2)
        val created = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer)
                .build()
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(created.audioSessionId)?.let { canceler ->
                canceler.enabled = true
                echo = canceler
            }
        }
        synchronized(lock) {
            record = created
            running = true
        }
        val started = runCatching { created.startRecording() }.isSuccess
        if (!started || created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stop()
            return false
        }
        val worker = Thread({
            val frame = ByteArray(sampleRate / 10 * 2)
            while (running) {
                val read = created.read(frame, 0, frame.size)
                if (read <= 0) continue
                if (muted) continue
                val chunk = if (read == frame.size) frame.copyOf() else frame.copyOf(read)
                onFrame(chunk, PcmLevel.rms16(chunk))
            }
        }, "jarvis-mic")
        thread = worker
        worker.start()
        return true
    }

    fun stop() {
        val current: AudioRecord?
        val worker: Thread?
        synchronized(lock) {
            running = false
            current = record
            record = null
            worker = thread
            thread = null
        }
        worker?.join(400)
        runCatching { current?.stop() }
        echo?.release()
        echo = null
        current?.release()
    }
}
