package com.jarvis.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Syllable-shaped level while Android TTS is talking. TTS does not expose playback RMS.
 * The shape matches the prototype's simVoice(): phrases around 1 Hz, syllables around 5 Hz.
 */
object SpeechEnvelope {
    fun levelAt(elapsedSeconds: Float): Float {
        val t = elapsedSeconds
        val phrase = if (sin(t * 0.9f) + sin(t * 0.37f + 1f) > -0.6f) 1f else 0.1f
        val raw = sin(t * PI.toFloat() * 4.7f) * 0.6f + sin(t * PI.toFloat() * 2.9f + 0.7f) * 0.4f
        val syllable = raw.coerceAtLeast(0f).pow(1.6f)
        return (phrase * (0.15f + syllable * 0.85f)).coerceIn(0f, 1f)
    }
}
