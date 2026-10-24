package com.jarvis.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyEndpointerTest {
    @Test
    fun silenceUntilTheTimeoutEndsTheListen() {
        val end = EnergyEndpointer(noSpeechTimeoutMs = 1_000, speechHoldMs = 100)
        assertEquals(EnergyEndpointer.Decision.CONTINUE, end.onRms(0, -2f))
        assertEquals(EnergyEndpointer.Decision.CONTINUE, end.onRms(900, -2f))
        assertEquals(EnergyEndpointer.Decision.END, end.onRms(1_000, -2f))
        assertFalse(end.heardSpeech)
    }

    @Test
    fun speechThenSilenceEndsAfterTheHold() {
        val end = EnergyEndpointer(
            speechDb = 2f,
            silenceDb = 0.5f,
            speechHoldMs = 100,
            silenceHoldMs = 400,
            noSpeechTimeoutMs = 5_000,
        )
        assertEquals(EnergyEndpointer.Decision.CONTINUE, end.onRms(0, 4f))
        assertEquals(EnergyEndpointer.Decision.CONTINUE, end.onRms(100, 4f))
        assertTrue(end.heardSpeech)
        assertEquals(EnergyEndpointer.Decision.CONTINUE, end.onRms(200, 0f))
        assertEquals(EnergyEndpointer.Decision.END, end.onRms(600, 0f))
    }
}
