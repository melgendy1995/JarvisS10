package com.jarvis.core.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerLanguageTest {
    @Test
    fun arabicScriptMeansArabic() {
        assertTrue(AnswerLanguage.isArabic("كم الساعة"))
        assertTrue(AnswerLanguage.isArabic("ما آخر أخبار السعودية؟"))
        assertTrue(AnswerLanguage.isArabic("وش صار اليوم"))
    }

    @Test
    fun latinMeansEnglishEvenWithNumbers() {
        assertFalse(AnswerLanguage.isArabic("who won the match?"))
        assertFalse(AnswerLanguage.isArabic("gold price 2026"))
        assertFalse(AnswerLanguage.isArabic("what's 375 - 75?"))
    }

    @Test
    fun instructionsArePerRequest() {
        assertEquals("Reply in Arabic.", AnswerLanguage.instruction("ما الأخبار؟"))
        assertEquals("Reply in English.", AnswerLanguage.instruction("what's the news?"))
    }
}
