package com.jarvis.provider.groq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperAcceptPolicyTest {
    @Test
    fun aSolidArabicTranscriptIsAccepted() {
        val decision = WhisperAcceptPolicy.evaluate("الطقس اليوم في الرياض", 0.02f, -0.35f)
        assertTrue(decision.accepted)
        assertEquals("ok", decision.reason)
    }

    @Test
    fun aSolidEnglishTranscriptIsAccepted() {
        val decision = WhisperAcceptPolicy.evaluate("What time is it?", 0.10f, -0.80f)
        assertTrue(decision.accepted)
    }

    @Test
    fun blankTextIsRejected() {
        assertEquals(
            WhisperAcceptPolicy.Decision(false, "blank"),
            WhisperAcceptPolicy.evaluate("", 0.02f, -0.3f),
        )
        assertEquals(
            WhisperAcceptPolicy.Decision(false, "blank"),
            WhisperAcceptPolicy.evaluate("  ", -1f, -1f),
        )
        assertEquals(
            WhisperAcceptPolicy.Decision(false, "blank"),
            WhisperAcceptPolicy.evaluate("a", -1f, -1f),
        )
    }

    @Test
    fun highNoSpeechProbabilityIsRejected() {
        val decision = WhisperAcceptPolicy.evaluate("hello there", 0.75f, -0.4f)
        assertFalse(decision.accepted)
        assertEquals("no-speech", decision.reason)
    }

    @Test
    fun lowAvgLogprobIsRejected() {
        val decision = WhisperAcceptPolicy.evaluate("hello there", 0.2f, -1.4f)
        assertFalse(decision.accepted)
        assertEquals("logprob", decision.reason)
    }

    @Test
    fun missingSegmentStatisticsNeverReject() {
        val decision = WhisperAcceptPolicy.evaluate("hello there", -1f, -1f)
        assertTrue(decision.accepted)
    }

    @Test
    fun silenceHallucinationsAreRejected() {
        for (phrase in listOf(
            "Thank you.",
            "Thanks for watching",
            "شكرا",
            "ترجمة نانسي قنقر",
            "اشترك في القناة",
            "Please subscribe. Thanks for watching!",
            "Subtitles by the community",
        )) {
            val decision = WhisperAcceptPolicy.evaluate(phrase, -1f, -1f)
            assertFalse("phrase: $phrase", decision.accepted)
            assertEquals("hallucination", decision.reason)
        }
    }

    @Test
    fun realUtterancesContainingThanksWordsStillPass() {
        // The word pattern must not eat longer real questions.
        assertTrue(WhisperAcceptPolicy.evaluate("thank you very much for the last answer", -1f, -1f).accepted)
    }

    @Test
    fun otherLanguagesOrScriptsAreRejected() {
        assertEquals("language", WhisperAcceptPolicy.evaluate("はい", 0.1f, -0.5f, "Japanese").reason)
        assertEquals("language", WhisperAcceptPolicy.evaluate("はい", 0.1f, -0.5f, null).reason)
        assertTrue(WhisperAcceptPolicy.evaluate("what time is it", 0.1f, -0.3f, "English").accepted)
        assertTrue(WhisperAcceptPolicy.evaluate("كم الساعة الآن", 0.1f, -0.3f, "Arabic").accepted)
        assertTrue(WhisperAcceptPolicy.evaluate("كم الساعة", 0.1f, -0.3f, "ar").accepted)
        assertFalse(WhisperAcceptPolicy.supportedLanguage("French"))
    }
}
