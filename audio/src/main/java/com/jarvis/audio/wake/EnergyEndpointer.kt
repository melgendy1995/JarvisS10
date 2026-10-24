package com.jarvis.audio.wake

/**
 * End of speech from recognizer RMS (dB), not a second microphone.
 * Silero VAD needs PCM from our own AudioRecord, and a second recorder
 * silences SpeechRecognizer on this phone.
 */
class EnergyEndpointer(
    private val speechDb: Float = 2.5f,
    private val silenceDb: Float = 0.5f,
    private val speechHoldMs: Long = 160,
    private val silenceHoldMs: Long = 900,
    private val noSpeechTimeoutMs: Long = 6_000,
    private val maxUtteranceMs: Long = 18_000,
) {
    private var startMs: Long = -1
    private var speechSince: Long = -1
    private var silenceSince: Long = -1
    private var heard = false

    val heardSpeech: Boolean get() = heard

    fun onRms(nowMs: Long, rmsDb: Float): Decision {
        if (startMs < 0) startMs = nowMs
        val elapsed = nowMs - startMs
        if (!heard) {
            if (rmsDb >= speechDb) {
                if (speechSince < 0) speechSince = nowMs
                if (nowMs - speechSince >= speechHoldMs) heard = true
            } else {
                speechSince = -1
            }
            if (elapsed >= noSpeechTimeoutMs) return Decision.END
            return Decision.CONTINUE
        }
        if (elapsed >= maxUtteranceMs) return Decision.END
        if (rmsDb <= silenceDb) {
            if (silenceSince < 0) silenceSince = nowMs
            if (nowMs - silenceSince >= silenceHoldMs) return Decision.END
        } else {
            silenceSince = -1
        }
        return Decision.CONTINUE
    }

    enum class Decision { CONTINUE, END }
}
