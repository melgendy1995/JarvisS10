package com.jarvis.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Jarvis's own utterance capture for the Whisper primary path (ar/auto).
 * One-turn AudioRecord: 16 kHz mono PCM16 from VOICE_RECOGNITION with AEC/NS
 * when the phone offers them, end-pointed by [UtteranceEndpointer].
 * The wake engine's recorder must already be released when [capture] runs,
 * exactly like the handover to SpeechRecognizer today.
 */
class UtteranceMic(
    private val context: Context,
    private val sampleRate: Int = 16_000,
) {
    data class Recording(
        val pcm: ByteArray,
        val reason: String,
        val ms: Long,
        /** Loudest frame, PcmLevel.rms16 units; -1 when nothing was read. */
        val peak: Float = -1f,
        /** Learned noise floor, PcmLevel.rms16 units; -1 when untrained. */
        val floor: Float = -1f,
    )

    private val frameSamples = sampleRate / 10
    private val frameBytes = frameSamples * 2
    private val frameMs = 100L
    private val lock = Any()
    private var record: AudioRecord? = null

    @Volatile private var stopping = false

    /** Cancel a running [capture]; the recorder is released either way. */
    fun stop() {
        stopping = true
        synchronized(lock) {
            runCatching { record?.stop() }
        }
    }

    /**
     * Blocks for one utterance. Reason is silence, cap, or no-speech; "mic" when
     * the AudioRecord never opened. Release always happens in the finally block.
     */
    fun capture(onLevel: (Float) -> Unit = {}): Recording {
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val opened = if (micAllowed) openRecord() else null
        if (opened == null) {
            return Recording(ByteArray(0), "mic", 0L)
        }
        val endpointer = UtteranceEndpointer()
        val prerollFrames = endpointer.prerollFrames(frameMs)
        val preroll = ArrayDeque<ByteArray>(prerollFrames)
        val utterance = ByteArrayOutputStream()
        // Everything heard before onset, for the clear-peak safety net.
        val waitWindow = ByteArrayOutputStream()
        var decision = UtteranceEndpointer.Decision.CAP
        var frames = 0L
        var heard = false
        try {
            stopping = false
            while (!stopping) {
                val frame = ByteArray(frameBytes)
                val read = synchronized(lock) { opened.record.read(frame, 0, frameBytes) }
                if (read < frameBytes) continue
                frames++
                decision = endpointer.onRms(PcmLevel.rms16(frame), frameMs)
                if (!heard) waitWindow.write(frame)
                if (!heard && decision == UtteranceEndpointer.Decision.CONTINUE) {
                    preroll.addLast(frame)
                    while (preroll.size > prerollFrames) preroll.removeFirst()
                    if (!endpointer.heardSpeech) continue
                    heard = true
                    while (preroll.isNotEmpty()) utterance.write(preroll.removeFirst())
                    utterance.write(frame)
                    continue
                }
                utterance.write(frame)
                if (decision != UtteranceEndpointer.Decision.CONTINUE) break
            }
        } finally {
            synchronized(lock) {
                runCatching { opened.record.stop() }
                record = null
            }
            opened.echo?.release()
            opened.noise?.release()
            opened.record.release()
        }
        val peak = endpointer.peakLevel
        val floor = endpointer.floorLevel
        if (decision == UtteranceEndpointer.Decision.NO_SPEECH && !heard && endpointer.clearPeak()) {
            return Recording(waitWindow.toByteArray(), REASON_PEAK, frames * frameMs, peak, floor)
        }
        val reason = when {
            stopping && decision == UtteranceEndpointer.Decision.CONTINUE -> "cap"
            decision == UtteranceEndpointer.Decision.NO_SPEECH -> "no-speech"
            decision == UtteranceEndpointer.Decision.SILENCE -> "silence"
            else -> "cap"
        }
        return Recording(utterance.toByteArray(), reason, frames * frameMs, peak, floor)
    }

    private class Opened(
        val record: AudioRecord,
        val echo: AcousticEchoCanceler?,
        val noise: NoiseSuppressor?,
    )

    private fun openRecord(): Opened? {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        val bytes = maxOf(min, frameSamples * 2 * 4)
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val created = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bytes)
                    .build()
            } catch (_: SecurityException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                continue
            }
            val echo = if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(created.audioSessionId)?.also { it.enabled = true }
            } else {
                null
            }
            val noise = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(created.audioSessionId)?.also { it.enabled = true }
            } else {
                null
            }
            val started = runCatching { created.startRecording() }.isSuccess &&
                created.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (!started) {
                echo?.release()
                noise?.release()
                created.release()
                continue
            }
            synchronized(lock) { record = created }
            return Opened(created, echo, noise)
        }
        return null
    }

    companion object {
        /** No onset, but a clear peak: the whole wait window goes to Whisper. */
        const val REASON_PEAK = "no-onset-peak"
    }
}
