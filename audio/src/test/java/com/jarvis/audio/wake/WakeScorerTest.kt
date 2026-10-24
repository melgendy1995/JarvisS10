package com.jarvis.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeScorerTest {
    @Test
    fun warmupZerosTheFirstFiveFrames() {
        val scorer = WakeScorer()
        repeat(5) {
            assertEquals(0f, scorer.accept(1f, 0.35f), 0.0001f)
        }
    }

    @Test
    fun oneFrameAtThresholdTriggers() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0f, scorer.accept(0.34f), 0.0001f)
        assertEquals(0.35f, WakeScorer(warmup = 0).accept(0.35f), 0.0001f)
        assertEquals(0.493f, WakeScorer(warmup = 0).accept(0.493f), 0.0001f)
    }

    @Test
    fun twoOfTheLastFourFramesAtSoftThresholdTrigger() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0f, scorer.accept(0.31f), 0.0001f)
        assertEquals(0.31f, scorer.accept(0.31f), 0.0001f)
    }

    @Test
    fun twoSoftHitsWithAGapInAWindowOfFourTrigger() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0f, scorer.accept(0.31f), 0.0001f)
        assertEquals(0f, scorer.accept(0.10f), 0.0001f)
        assertEquals(0f, scorer.accept(0.10f), 0.0001f)
        assertEquals(0.32f, scorer.accept(0.32f), 0.0001f)
    }

    @Test
    fun aSingleSoftFrameDoesNotTrigger() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0f, scorer.accept(0.31f), 0.0001f)
        assertEquals(0f, scorer.accept(0.10f), 0.0001f)
        assertEquals(0f, scorer.accept(0.10f), 0.0001f)
        assertEquals(0f, scorer.accept(0.10f), 0.0001f)
        assertEquals(0f, scorer.accept(0.10f), 0.0001f)
    }

    @Test
    fun refractorySuppressesHitsForTwoSeconds() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0.9f, scorer.accept(0.9f, frameSeconds = 0.08f, refractorySeconds = 0.24f), 0.0001f)
        assertEquals(0f, scorer.accept(0.9f, frameSeconds = 0.08f, refractorySeconds = 0.24f), 0.0001f)
        assertEquals(0f, scorer.accept(0.9f, frameSeconds = 0.08f, refractorySeconds = 0.24f), 0.0001f)
        assertEquals(0.9f, scorer.accept(0.9f, frameSeconds = 0.08f, refractorySeconds = 0.24f), 0.0001f)
    }

    @Test
    fun speakingPathIgnoresTheSoftWindow() {
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0f, scorer.accept(0.31f, threshold = 0.35f, allowSoft = false), 0.0001f)
        assertEquals(0f, scorer.accept(0.31f, threshold = 0.35f, allowSoft = false), 0.0001f)
        assertEquals(0f, scorer.accept(0.34f, threshold = 0.35f, allowSoft = false), 0.0001f)
        assertEquals(0.70f, scorer.accept(0.70f, threshold = 0.70f, allowSoft = false), 0.0001f)
    }

    @Test
    fun defaultThresholdIsLowEnoughForARoomVoice() {
        assertTrue(WakeConfig.THRESHOLD <= 0.36f)
        assertTrue(WakeConfig.SOFT_THRESHOLD <= 0.30f)
        val scorer = WakeScorer(warmup = 0)
        assertEquals(0.445f, scorer.accept(0.445f), 0.0001f)
    }
}
