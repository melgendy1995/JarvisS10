package com.jarvis.audio

import kotlin.math.ceil

/**
 * End-pointing for Jarvis's own utterance capture (Whisper primary in ar/auto).
 * Frame RMS energy against an adaptive noise floor: wait for speech onset, stop
 * after continuous silence, hard cap the total. Pure Kotlin so the decisions are
 * unit-testable.
 *
 * Floor rules: until a below-speech frame has been seen the floor is untrained
 * and [ABS_SPEECH] alone gates the onset, so speech on the very first frame is
 * still a candidate. Candidate frames never raise the floor, so loud input does
 * not poison the noise estimate.
 */
class UtteranceEndpointer(
    private val noSpeechTimeoutMs: Long = 5_000,
    private val silenceHoldMs: Long = 900,
    private val maxUtteranceMs: Long = 8_000,
    private val speechHoldMs: Long = 120,
    private val prerollMs: Long = 300,
) {
    private var elapsedMs = 0L
    private var speechMs = 0L
    private var silenceMs = 0L
    private var floor = -1f
    private var heard = false

    private var peak = 0f

    val heardSpeech: Boolean get() = heard

    /** Loudest frame so far, in PcmLevel.rms16 units. */
    val peakLevel: Float get() = peak

    /** Learned noise floor, or -1 when no quiet frame has been seen yet. */
    val floorLevel: Float get() = floor

    /**
     * No onset, but the wait window held a clear peak above the floor: worth one
     * Whisper pass, whose confidence gate rejects plain noise.
     */
    fun clearPeak(): Boolean {
        val base = if (floor < 0f) MIN_FLOOR else floor
        return peak >= maxOf(PEAK_MIN, base * PEAK_TIMES)
    }

    enum class Decision {
        CONTINUE,
        NO_SPEECH,
        SILENCE,
        CAP,
    }

    /** Frames the recorder must keep before speech onset to add [prerollMs] pre-roll. */
    fun prerollFrames(frameMs: Long): Int {
        if (frameMs <= 0) return 0
        return ceil(prerollMs.toDouble() / frameMs).toInt().coerceAtLeast(1)
    }

    /** [rms] is the 0..1 frame level from PcmLevel.rms16; [frameMs] the frame duration. */
    fun onRms(rms: Float, frameMs: Long): Decision {
        elapsedMs += frameMs
        if (rms > peak) peak = rms
        if (!heard) {
            if (rms >= onsetThreshold()) {
                speechMs += frameMs
                if (speechMs >= speechHoldMs) heard = true
            } else {
                learn(rms)
                speechMs = 0
                if (elapsedMs >= noSpeechTimeoutMs) return Decision.NO_SPEECH
            }
            return Decision.CONTINUE
        }
        val quiet = maxOf(quietLevel() * SILENCE_TIMES, quietLevel() + SILENCE_STEP)
        if (rms < quiet) {
            learn(rms)
            silenceMs += frameMs
            if (silenceMs >= silenceHoldMs) return Decision.SILENCE
        } else {
            silenceMs = 0
        }
        if (elapsedMs >= maxUtteranceMs) return Decision.CAP
        return Decision.CONTINUE
    }

    /** Onset level from the noise floor; untrained floors use the absolute level. */
    private fun onsetThreshold(): Float {
        if (floor < 0f) return ABS_SPEECH
        return maxOf(floor * ONSET_TIMES, floor + ONSET_STEP, ABS_SPEECH)
    }

    /** Silence level in the heard state; untrained floors use a low absolute level. */
    private fun quietLevel(): Float {
        if (floor < 0f) return ABS_QUIET
        return floor.coerceAtLeast(MIN_FLOOR)
    }

    /** Steep when the level drops, slow when it rises so speech never lifts the floor. */
    private fun learn(rms: Float) {
        if (floor < 0f) {
            if (rms < ABS_SPEECH) floor = rms.coerceAtLeast(MIN_FLOOR)
            return
        }
        floor = if (rms < floor) floor * 0.8f + rms * 0.2f else floor * 0.98f + rms * 0.02f
    }

    private companion object {
        // Levels are PcmLevel.rms16 units (raw RMS x3). On the S10 VOICE_RECOGNITION
        // mic with AEC/NS a quiet room sits near 0.01-0.02 and speech at 1-2 m
        // near 0.04-0.15, so onset is the adaptive floor times a factor with a
        // low absolute minimum.
        const val MIN_FLOOR = 0.003f
        /** Minimum onset level, also used before any floor is learned. */
        const val ABS_SPEECH = 0.03f
        /** Silence while no floor has been learned yet. */
        const val ABS_QUIET = 0.02f
        const val ONSET_TIMES = 2.5f
        const val ONSET_STEP = 0.012f
        const val SILENCE_TIMES = 1.8f
        const val SILENCE_STEP = 0.006f
        /** A wait-window peak this loud (and [PEAK_TIMES] over the floor) still goes to Whisper. */
        const val PEAK_MIN = 0.025f
        const val PEAK_TIMES = 2.0f
    }
}
