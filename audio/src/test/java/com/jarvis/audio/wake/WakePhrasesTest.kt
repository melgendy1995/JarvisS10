package com.jarvis.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhrasesTest {
    @Test
    fun heyJarvisBargesInAndJarvisStopOnlyStops() {
        assertEquals(WakePhrases.Barge.WAKE, WakePhrases.barge("Hey Jarvis", "The time is three."))
        assertEquals(WakePhrases.Barge.WAKE, WakePhrases.barge("hey jarvis what time is it", "hello"))
        assertEquals("what time is it", WakePhrases.remainder("hey jarvis what time is it"))
        assertEquals(WakePhrases.Barge.STOP, WakePhrases.barge("Jarvis, stop!", "The time is three."))
        assertEquals(WakePhrases.Barge.STOP, WakePhrases.barge("hey jarvis stop", "hello"))
        assertNull(WakePhrases.remainder("hey jarvis stop"))
        assertEquals(WakePhrases.Barge.NONE, WakePhrases.barge("jarvis", "hello"))
        assertTrue(WakePhrases.isStop("stop jarvis"))
    }

    @Test
    fun aLongEchoOfTheAnswerIsIgnored() {
        val spoken = "the capital of france is paris and the river is the seine"
        assertEquals(WakePhrases.Barge.NONE, WakePhrases.barge(spoken, spoken))
    }
}
