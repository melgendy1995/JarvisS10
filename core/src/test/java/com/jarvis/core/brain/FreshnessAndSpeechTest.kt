package com.jarvis.core.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessAndSpeechTest {
    @Test
    fun freshEnglishQuestionsNeedSearch() {
        assertTrue(Freshness.needsLiveInfo("what's the latest news about Saudi Arabia"))
        assertTrue(Freshness.needsLiveInfo("what is the price of gold today"))
        assertTrue(Freshness.needsLiveInfo("who won the match"))
        assertTrue(Freshness.needsLiveInfo("what's the weather now"))
    }

    @Test
    fun stableQuestionsStayOnTheNormalPath() {
        assertFalse(Freshness.needsLiveInfo("what is the capital of France"))
        assertFalse(Freshness.needsLiveInfo("tell me a joke"))
        assertFalse(Freshness.needsLiveInfo("set a timer for 5 minutes"))
        assertFalse(Freshness.needsLiveInfo("كم الساعة"))
    }

    @Test
    fun freshArabicQuestionsNeedSearch() {
        assertTrue(Freshness.needsLiveInfo("ما آخر أخبار السعودية"))
        assertTrue(Freshness.needsLiveInfo("كم سعر الذهب اليوم"))
        assertTrue(Freshness.needsLiveInfo("كيف الطقس الآن"))
    }

    @Test
    fun speechDropsUrlsMarkdownAndCitations() {
        assertEquals(
            "Gold is about 4300 dollars.",
            SpokenText.forSpeech("Gold is about 4300 dollars. https://example.com/gold?q=1"),
        )
        assertEquals(
            "See Reuters today.",
            SpokenText.forSpeech("See [Reuters](https://www.reuters.com/world) today."),
        )
        assertEquals(
            "Spain won.",
            SpokenText.forSpeech("Spain won [1]."),
        )
        assertEquals(
            "Spain won.",
            SpokenText.forSpeech("Spain won 【1†source】."),
        )
        assertEquals(
            "الرياض اليوم دافئة.",
            SpokenText.forSpeech("**الرياض** اليوم دافئة."),
        )
    }
}
