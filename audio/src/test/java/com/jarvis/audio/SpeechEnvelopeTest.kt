package com.jarvis.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEnvelopeTest {
    @Test
    fun levelStaysInsideZeroToOne() {
        var peak = 0f
        var t = 0f
        while (t < 4f) {
            val level = SpeechEnvelope.levelAt(t)
            assertTrue(level in 0f..1f)
            if (level > peak) peak = level
            t += 0.02f
        }
        assertTrue(peak > 0.5f)
    }
}
