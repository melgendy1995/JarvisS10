package com.jarvis.core.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleAnswerTest {
    @Test
    fun englishNoRealtimeAdmissionsWantARetry() {
        assertTrue(StaleAnswer.needsLiveRetry("I don't have access to real-time information."))
        assertTrue(StaleAnswer.needsLiveRetry("I do not have access to live data right now."))
        assertTrue(StaleAnswer.needsLiveRetry("As of my last update, he was champion."))
        assertTrue(StaleAnswer.needsLiveRetry("My knowledge cutoff limits me."))
        assertTrue(StaleAnswer.needsLiveRetry("I cannot browse the web from here."))
    }

    @Test
    fun arabicNoRealtimeAdmissionsWantARetry() {
        assertTrue(StaleAnswer.needsLiveRetry("لا أستطيع الوصول إلى الإنترنت."))
        assertTrue(StaleAnswer.needsLiveRetry("ليس لدي معلومات حديثة عن هذا."))
    }

    @Test
    fun realAnswersStayPut() {
        assertFalse(StaleAnswer.needsLiveRetry("Saudi Arabia won 3-1 yesterday."))
        assertFalse(StaleAnswer.needsLiveRetry("The dollar is at 3.75 riyals today."))
        assertFalse(StaleAnswer.needsLiveRetry("المباراة انتهت بفوز الهلال على النصر."))
        assertFalse(StaleAnswer.needsLiveRetry("Flight updates arrive at noon."))
    }

    @Test
    fun detectionIsCaseInsensitive() {
        assertEquals(
            StaleAnswer.needsLiveRetry("REAL-TIME data is beyond me."),
            StaleAnswer.needsLiveRetry("real-time data is beyond me."),
        )
    }
}
