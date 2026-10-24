package com.jarvis.orb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RingLookTest {
    @Test
    fun looksAreStableInstances() {
        assertSame(OrbState.IDLE.look(), OrbState.IDLE.look())
        assertSame(OrbState.ERROR.look(), OrbState.ERROR.look())
    }

    @Test
    fun statesHaveDistinctColorAndMotion() {
        val idle = OrbState.IDLE.look()
        val listen = OrbState.LISTENING.look()
        val think = OrbState.THINKING.look()
        val speak = OrbState.SPEAKING.look()
        val error = OrbState.ERROR.look()

        assertEquals(0f, idle.expand, 0f)
        assertEquals(1f, listen.expand, 0f)
        assertTrue(listen.hotB > listen.hotR)
        assertTrue(think.spin > listen.spin)
        assertTrue(think.spin > speak.spin)
        assertTrue(think.hotR > 0.8f && think.hotB > 0.8f && think.hotG < 0.3f)
        assertTrue(think.vortex > listen.vortex)
        assertTrue(speak.pulse > idle.pulse)
        assertTrue(speak.hotR > speak.hotB)
        assertTrue(speak.hotG > speak.hotB)
        assertEquals(1f, error.flash, 0f)
        assertEquals(0f, idle.flash, 0f)
        assertTrue(error.hotR > error.hotG)
        assertTrue(idle.breatheHz < 0.3f)
        assertTrue(idle.coreB > idle.coreR)
    }

    @Test
    fun approachEasesHalfwayWithoutAllocatingANewLook() {
        val idle = OrbState.IDLE.look()
        val listen = OrbState.LISTENING.look()
        val params = RingParams.from(idle)
        params.approach(listen, 0.5f)
        assertEquals(idle.spin + (listen.spin - idle.spin) * 0.5f, params.spin, 1e-4f)
        assertEquals(idle.expand + (listen.expand - idle.expand) * 0.5f, params.expand, 1e-4f)
        assertEquals(idle.hotG + (listen.hotG - idle.hotG) * 0.5f, params.hotG, 1e-4f)
        assertSame(idle, OrbState.IDLE.look())
    }

    @Test
    fun previewLevelStaysInRangeAndListeningMoves() {
        var low = 1f
        var high = 0f
        var speakHigh = 0f
        for (step in 0..40) {
            val t = step / 10f
            for (state in OrbState.entries) {
                val level = previewLevel(state, t)
                assertTrue(level in 0f..1f)
            }
            val listen = previewLevel(OrbState.LISTENING, t)
            if (listen < low) low = listen
            if (listen > high) high = listen
            val speak = previewLevel(OrbState.SPEAKING, t)
            if (speak > speakHigh) speakHigh = speak
        }
        assertTrue(high - low > 0.6f)
        assertTrue(speakHigh > 0.5f)
    }
}
