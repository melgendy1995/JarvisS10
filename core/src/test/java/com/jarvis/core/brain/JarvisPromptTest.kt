package com.jarvis.core.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisPromptTest {
    @Test
    fun cloudPromptIsAGeneralAssistantInJarvisVoice() {
        val prompt = JarvisPrompt.SYSTEM
        val lower = prompt.lowercase()
        assertTrue(lower.contains("j.a.r.v.i.s"))
        assertTrue(lower.contains("witty"))
        assertTrue(lower.contains("concise"))
        assertTrue(lower.contains("calm"))
        assertTrue(lower.contains("british-butler"))
        assertTrue(lower.contains("mahmoud"))
        assertTrue(lower.contains("general-knowledge"))
        assertTrue(lower.contains("english or arabic"))
        assertTrue(lower.contains("matching the user's language"))
        assertTrue(lower.contains("answer any question"))
        assertTrue(lower.contains("explain"))
        assertTrue(lower.contains("chat"))
        assertTrue(lower.contains("plan"))
        assertTrue(lower.contains("tell the time"))
        assertTrue(lower.contains("timer"))
        assertTrue(lower.contains("flashlight"))
        assertTrue(lower.contains("remember, recall, or forget"))
        assertTrue(lower.contains("one to three short sentences"))
        assertTrue(lower.contains("no markdown"))
        assertFalse(prompt.contains("*"))
        assertFalse(lower.contains("voice assistant on a phone"))
        assertFalse(lower.contains("only do"))
        assertTrue(lower.contains("you cannot control the phone"))
        assertTrue(lower.contains("never claim you stopped"))
        assertTrue(lower.contains("try saying 'pause youtube music'"))
        assertTrue(prompt.contains("وقف يوتيوب ميوزك"))
        assertTrue(prompt.length < PromptFitter.LIVE_CHAR_BUDGET / 2)
    }

    @Test
    fun fitterKeepsTheCloudPromptAndAppendsPersonaFactsAndSummary() {
        val text = PromptFitter().memorySystem(
            baseSystem = JarvisPrompt.SYSTEM,
            persona = "Lives in Riyadh.",
            facts = listOf(MemoryFact("tea", "mint", pinned = true)),
            summary = "Talked about the trip.",
            charBudget = PromptFitter.LIVE_CHAR_BUDGET,
        )
        assertTrue(text.startsWith(JarvisPrompt.SYSTEM))
        assertTrue(text.contains("About the user:\nLives in Riyadh."))
        assertTrue(text.contains("tea: mint"))
        assertTrue(text.contains("Talked about the trip."))
        assertTrue(text.length <= PromptFitter.LIVE_CHAR_BUDGET)
        assertEquals(JarvisPrompt.SYSTEM, text.substringBefore("\n\n"))
    }
}
