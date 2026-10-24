package com.jarvis.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeScorerTest {
    @Test
    fun warmupZerosTheFirstFiveFrames() {
        val scorer = WakeScorer()
        repeat(5) {
            assertEquals(0f, scorer.accept(1f, 0.5f), 0.0001f)
        }
    }

    @Test
    fun debounceSuppressesTheSameWakeInsideTheWindow() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0.9f, scorer.accept(0.9f, 0.5f, frameSeconds = 0.08f, debounceSeconds = 0.16f), 0.0001f)
        assertEquals(0f, scorer.accept(0.9f, 0.5f, frameSeconds = 0.08f, debounceSeconds = 0.16f), 0.0001f)
        assertEquals(0f, scorer.accept(0.9f, 0.5f, frameSeconds = 0.08f, debounceSeconds = 0.16f), 0.0001f)
        assertEquals(0.9f, scorer.accept(0.9f, 0.5f, frameSeconds = 0.08f, debounceSeconds = 0.16f), 0.0001f)
    }
}
