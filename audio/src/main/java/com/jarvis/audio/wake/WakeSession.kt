package com.jarvis.audio.wake

/**
 * Occupancy of the shared microphone. The wake recorder captures only in
 * [Phase.PASSIVE]. Hold-to-talk, command listen, thinking, and speaking all
 * own the mic; every one of those paths has to return here.
 */
class WakeSession {
    enum class Phase { PASSIVE, HOLDING, LISTENING, THINKING, SPEAKING }

    @Volatile
    var phase: Phase = Phase.PASSIVE
        private set

    val shouldCapture: Boolean
        get() = phase == Phase.PASSIVE

    fun isBusy(): Boolean = when (phase) {
        Phase.HOLDING, Phase.LISTENING, Phase.THINKING, Phase.SPEAKING -> true
        Phase.PASSIVE -> false
    }

    fun enter(next: Phase) {
        phase = next
    }

    fun holdStart() = enter(Phase.HOLDING)

    /** Finger up; SpeechRecognizer may still be finishing the utterance. */
    fun holdRelease() = enter(Phase.LISTENING)

    fun cancel() = enter(Phase.PASSIVE)

    fun error() = enter(Phase.PASSIVE)

    fun emptyTranscript() = enter(Phase.PASSIVE)

    fun recognizerBusy() = enter(Phase.PASSIVE)

    fun commandHeard() = enter(Phase.THINKING)

    fun listen() = enter(Phase.LISTENING)

    fun speak() = enter(Phase.SPEAKING)

    fun followUpEnded() = enter(Phase.PASSIVE)

    fun ttsDone(followUp: Boolean) = enter(if (followUp) Phase.LISTENING else Phase.PASSIVE)

    fun bargeIn() = enter(Phase.LISTENING)

    fun bargeStop() = enter(Phase.PASSIVE)

    fun appVisible() {
        if (!isBusy()) enter(Phase.PASSIVE)
    }

    fun watchdogShouldRestart(
        nowMs: Long,
        lastFrameMs: Long,
        timeoutMs: Long = WakeConfig.WATCHDOG_MS,
    ): Boolean {
        if (isBusy()) return false
        return nowMs - lastFrameMs >= timeoutMs
    }
}
