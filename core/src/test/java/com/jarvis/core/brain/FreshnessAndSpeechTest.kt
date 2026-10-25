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
    fun newEnglishLivePhrasesNeedSearch() {
        assertTrue(Freshness.needsLiveInfo("what is the exchange rate today"))
        assertTrue(Freshness.needsLiveInfo("how much is a bitcoin worth"))
        assertTrue(Freshness.needsLiveInfo("who is the champion"))
        assertTrue(Freshness.needsLiveInfo("what happened in Riyadh"))
        assertTrue(Freshness.needsLiveInfo("what's going on in the market"))
        assertTrue(Freshness.needsLiveInfo("what is the score of the game"))
        assertTrue(Freshness.needsLiveInfo("who was the winner of the fixture"))
        assertTrue(Freshness.needsLiveInfo("did the dollar rate change"))
        assertTrue(Freshness.needsLiveInfo("is there a new release today"))
        assertTrue(Freshness.needsLiveInfo("bitcoin price in 2026"))
    }

    @Test
    fun newArabicLivePhrasesNeedSearch() {
        assertTrue(Freshness.needsLiveInfo("كم سعر الدولار اليوم"))
        assertTrue(Freshness.needsLiveInfo("ماذا حدث في السعودية أمس"))
        assertTrue(Freshness.needsLiveInfo("من فاز في المباراة"))
        assertTrue(Freshness.needsLiveInfo("وش صار اليوم"))
        assertTrue(Freshness.needsLiveInfo("من هي الفائزة"))
        assertTrue(Freshness.needsLiveInfo("نتائج الانتخابات هذا الاسبوع"))
        assertTrue(Freshness.needsLiveInfo("سعر صرف اليورو بكره"))
        assertTrue(Freshness.needsLiveInfo("وش الجديد"))
    }

    @Test
    fun stableAramaicAndEnglishStayOffSearch() {
        assertFalse(Freshness.needsLiveInfo("what is the capital of France"))
        assertFalse(Freshness.needsLiveInfo("tell me a joke"))
        assertFalse(Freshness.needsLiveInfo("what is a black hole"))
        assertFalse(Freshness.needsLiveInfo("remind me to call the bank at nine"))
        assertFalse(Freshness.needsLiveInfo("كم الساعة"))
        assertFalse(Freshness.needsLiveInfo("احب طعم الماء البارد"))
        assertFalse(Freshness.needsLiveInfo("من ذكرني بأخذ قميصي"))
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
