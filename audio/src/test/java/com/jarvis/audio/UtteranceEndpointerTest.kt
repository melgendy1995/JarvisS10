package com.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceEndpointerTest {
    private val frame = 100L

    @Test
    fun silenceOnlyEndsWithNoSpeech() {
        val end = UtteranceEndpointer(noSpeechTimeoutMs = 1_000, silenceHoldMs = 300)
        repeat(9) { i ->
            assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        }
        assertEquals(UtteranceEndpointer.Decision.NO_SPEECH, end.onRms(0.01f, frame))
        assertFalse(end.heardSpeech)
    }

    @Test
    fun speechThenSilenceEndsBySilence() {
        val end = UtteranceEndpointer(silenceHoldMs = 500)
        end.onRms(0.01f, frame)
        end.onRms(0.01f, frame)
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.4f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.5f, frame))
        assertTrue(end.heardSpeech)
        // 400 ms of silence is still inside the hold; 600 ms ends it.
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.SILENCE, end.onRms(0.01f, frame))
    }

    @Test
    fun longSpeechHitsTheHardCap() {
        val end = UtteranceEndpointer(maxUtteranceMs = 2_000, silenceHoldMs = 900)
        repeat(2) {
            assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.5f, frame))
        }
        repeat(17) {
            assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.5f, frame))
        }
        assertEquals(UtteranceEndpointer.Decision.CAP, end.onRms(0.5f, frame))
    }

    @Test
    fun prerollFramesCoverTheRequestedWindow() {
        val end = UtteranceEndpointer(prerollMs = 300)
        assertEquals(3, end.prerollFrames(100))
        assertTrue(end.prerollFrames(80) >= 3)
    }

    @Test
    fun aShortBurstBelowTheSpeechHoldIsIgnored() {
        val end = UtteranceEndpointer(noSpeechTimeoutMs = 2_000, silenceHoldMs = 400)
        end.onRms(0.01f, frame)
        // A 100 ms burst is below the speech hold, so this stays waiting for speech.
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.6f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertFalse(end.heardSpeech)
    }

    @Test
    fun twoShortBurstsTogetherReadAsSpeech() {
        val end = UtteranceEndpointer(silenceHoldMs = 400)
        end.onRms(0.6f, frame)
        end.onRms(0.6f, frame)
        assertTrue(end.heardSpeech)
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame))
        assertEquals(UtteranceEndpointer.Decision.SILENCE, end.onRms(0.01f, frame))
    }

    @Test
    fun isolatedBurstsNeverReachingTheHoldTimeOutQuietly() {
        val end = UtteranceEndpointer(noSpeechTimeoutMs = 800, silenceHoldMs = 300)
        end.onRms(0.6f, frame)
        end.onRms(0.01f, frame)
        // A second isolated burst is still below the speech hold.
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.6f, frame))
        assertFalse(end.heardSpeech)
        repeat(4) { assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame)) }
        assertEquals(UtteranceEndpointer.Decision.NO_SPEECH, end.onRms(0.01f, frame))
    }

    @Test
    fun silenceAfterTheFloorAdaptsStillTimesOut() {
        val end = UtteranceEndpointer(noSpeechTimeoutMs = 900, silenceHoldMs = 300)
        assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.4f, frame))
        repeat(7) { assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.01f, frame)) }
        assertEquals(UtteranceEndpointer.Decision.NO_SPEECH, end.onRms(0.01f, frame))
        assertFalse(end.heardSpeech)
    }

    @Test
    fun realisticS10SpeechOverARoomFloorTriggersAndEndsBySilence() {
        val end = UtteranceEndpointer()
        repeat(5) { assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.012f, frame)) }
        assertFalse(end.heardSpeech)
        listOf(0.05f, 0.07f, 0.04f, 0.08f, 0.06f).forEach {
            assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(it, frame))
        }
        assertTrue(end.heardSpeech)
        repeat(8) { assertEquals(UtteranceEndpointer.Decision.CONTINUE, end.onRms(0.013f, frame)) }
        assertEquals(UtteranceEndpointer.Decision.SILENCE, end.onRms(0.013f, frame))
    }

    @Test
    fun aSteadyRoomFloorIsNoSpeechWithoutAClearPeak() {
        val end = UtteranceEndpointer()
        var last = UtteranceEndpointer.Decision.CONTINUE
        repeat(50) { last = end.onRms(0.015f, frame) }
        assertEquals(UtteranceEndpointer.Decision.NO_SPEECH, last)
        assertFalse(end.heardSpeech)
        assertFalse(end.clearPeak())
    }

    @Test
    fun aQuietButClearPeakWithoutOnsetIsFlaggedForWhisper() {
        val end = UtteranceEndpointer()
        var last = UtteranceEndpointer.Decision.CONTINUE
        repeat(50) { i -> last = end.onRms(if (i == 20) 0.028f else 0.008f, frame) }
        assertEquals(UtteranceEndpointer.Decision.NO_SPEECH, last)
        assertTrue(end.clearPeak())
        assertEquals(0.028f, end.peakLevel, 1e-6f)
    }
}
