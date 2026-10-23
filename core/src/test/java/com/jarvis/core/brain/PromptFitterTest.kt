package com.jarvis.core.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFitterTest {
    private val fitter = PromptFitter()

    @Test
    fun estimatesTokensAsCharsOverFour() {
        assertEquals(0, fitter.estimateTokens(""))
        assertEquals(1, fitter.estimateTokens("hello"))
        assertEquals(3, fitter.estimateTokens("a".repeat(12)))
    }

    @Test
    fun smallPromptFitsAndCountsSystemTurnsAndUserText() {
        val fitted = fitter.fit(
            system = "You are Jarvis.",
            turns = listOf(
                ConversationTurn(Speaker.USER, "Hi"),
                ConversationTurn(Speaker.ASSISTANT, "Hello"),
            ),
            userText = "And then?",
        )
        assertEquals("You are Jarvis.", fitted.system)
        assertEquals(3, fitted.turns.size)
        assertEquals(Speaker.USER, fitted.turns.last().speaker)
        assertEquals("And then?", fitted.turns.last().text)
        val chars = fitted.system.length + fitted.turns.sumOf { it.text.length }
        assertEquals(chars / 4, fitted.estimatedTokens)
        assertTrue(fitted.estimatedTokens < PromptFitter.TOKEN_BUDGET)
    }

    @Test
    fun dropsOldestTurnsUntilTheBudgetFits() {
        val budget = PromptFitter(tokenBudget = 10)
        val fitted = budget.fit(
            system = "S".repeat(8),
            turns = listOf(
                ConversationTurn(Speaker.USER, "A".repeat(20)),
                ConversationTurn(Speaker.ASSISTANT, "B".repeat(20)),
            ),
            userText = "U".repeat(8),
        )
        assertEquals("B".repeat(20), fitted.turns.first().text)
        assertEquals("U".repeat(8), fitted.turns.last().text)
        assertEquals(1, fitted.turns.count { it.text.startsWith("B") })
        assertTrue(fitted.turns.none { it.text.startsWith("A") })
        assertTrue(fitted.estimatedTokens <= 10)
    }

    @Test
    fun keepsTheTailOfTheNewestTurnWhenItAloneExceedsTheBudget() {
        val budget = PromptFitter(tokenBudget = 8)
        val fitted = budget.fit(
            system = "",
            turns = listOf(ConversationTurn(Speaker.ASSISTANT, "Z".repeat(100))),
            userText = "U".repeat(8),
        )
        val remembered = fitted.turns.first()
        assertEquals(Speaker.ASSISTANT, remembered.speaker)
        assertTrue(remembered.text.all { it == 'Z' })
        assertTrue(remembered.text.length < 100)
        assertTrue(fitted.estimatedTokens <= 8)
    }

    @Test
    fun userTextIsKeptAheadOfALongPersona() {
        val budget = PromptFitter(tokenBudget = 10)
        val fitted = budget.fit(
            system = "P".repeat(500),
            turns = listOf(ConversationTurn(Speaker.ASSISTANT, "recent reply")),
            userText = "what about that?",
        )
        assertEquals("what about that?", fitted.turns.last().text)
        assertTrue(fitted.system.length <= (10 * 4) / 2)
        assertTrue(fitted.system.all { it == 'P' })
        assertTrue(fitted.estimatedTokens <= 10)
    }

    @Test
    fun hugeUserTextIsTruncatedToTheBudget() {
        val budget = PromptFitter(tokenBudget = 5)
        val fitted = budget.fit(
            system = "persona",
            turns = listOf(ConversationTurn(Speaker.USER, "old")),
            userText = "Q".repeat(80),
        )
        assertEquals("Q".repeat(20), fitted.turns.single().text)
        assertEquals("", fitted.system)
        assertEquals(5, fitted.estimatedTokens)
    }

    @Test
    fun skipsBlankTurnsAndDoesNotRepeatTheCurrentUserLine() {
        val fitted = fitter.fit(
            system = "sys",
            turns = listOf(
                ConversationTurn(Speaker.USER, "   "),
                ConversationTurn(Speaker.ASSISTANT, "ok"),
                ConversationTurn(Speaker.USER, "now"),
            ),
            userText = "now",
        )
        assertEquals(listOf("ok", "now"), fitted.turns.map { it.text })
    }

    @Test
    fun defaultBudgetIsAboutFifteenHundredTokens() {
        val turns = List(40) { index ->
            ConversationTurn(
                speaker = if (index % 2 == 0) Speaker.USER else Speaker.ASSISTANT,
                text = "word ".repeat(80) + index,
            )
        }
        val fitted = fitter.fit(
            system = "persona ".repeat(100),
            turns = turns,
            userText = "continue",
        )
        assertTrue(fitted.estimatedTokens <= PromptFitter.TOKEN_BUDGET)
        assertTrue(fitted.estimatedTokens >= 1_000)
        assertEquals("continue", fitted.turns.last().text)
        assertTrue(fitted.turns.size < turns.size)
    }

    @Test
    fun emptyMemoryLeavesTheBasePromptUntouched() {
        assertEquals(
            "You are Jarvis.",
            fitter.memorySystem(baseSystem = "You are Jarvis.", charBudget = 3_000),
        )
    }

    @Test
    fun personaIsCappedAtTwoThousandCharacters() {
        val text = fitter.memorySystem(
            baseSystem = "",
            persona = "p".repeat(5_000),
            charBudget = 10_000,
        )
        assertTrue(text.startsWith("About the user:\n"))
        assertEquals(PromptFitter.PERSONA_CAP, text.removePrefix("About the user:\n").length)
    }

    @Test
    fun tightBudgetKeepsThePinnedFactAndDropsTheRest() {
        val text = fitter.memorySystem(
            baseSystem = "B",
            persona = "P",
            facts = listOf(
                MemoryFact("unpinned", "uuuu", pinned = false),
                MemoryFact("pinned", "pppp", pinned = true),
            ),
            summary = "S",
            charBudget = 43,
        )
        assertTrue(text.contains("pinned: pppp"))
        assertTrue(text.contains("About the user:"))
        assertFalse(text.contains("unpinned"))
        assertFalse(text.contains("Summary:"))
        assertTrue(text.length <= 43)
    }

    @Test
    fun recentTurnsKeepTheNewestLineInsideTheBudget() {
        val text = fitter.memorySystem(
            baseSystem = "B",
            priorTurns = listOf(
                ConversationTurn(Speaker.USER, "OLDWORD"),
                ConversationTurn(Speaker.USER, "NEWWORD"),
            ),
            charBudget = 30,
        )
        assertTrue(text.contains("NEWWORD"))
        assertFalse(text.contains("OLDWORD"))
        assertTrue(text.length <= 30)
    }

    @Test
    fun memorySystemStaysInsideTheLiveBudget() {
        val text = fitter.memorySystem(
            baseSystem = "base ".repeat(100),
            persona = "p".repeat(4_000),
            facts = List(20) { MemoryFact("k$it", "v".repeat(80), pinned = it == 0) },
            summary = "s".repeat(2_000),
            priorTurns = List(30) { ConversationTurn(Speaker.USER, "turn $it " + "w".repeat(100)) },
            charBudget = PromptFitter.LIVE_CHAR_BUDGET,
        )
        assertTrue(text.length <= PromptFitter.LIVE_CHAR_BUDGET)
        assertTrue(text.startsWith("base"))
        assertTrue(text.contains("About the user:"))
    }

    @Test
    fun fitMemoryKeepsPersonaFactsAndSummaryWhileDroppingOldTurns() {
        val fitted = fitter.fitMemory(
            baseSystem = "You are Jarvis.",
            persona = "Likes short answers.",
            facts = listOf(MemoryFact("city", "Riyadh", pinned = true)),
            summary = "Talked about tea.",
            turns = List(30) { index ->
                ConversationTurn(Speaker.USER, "old turn $index " + "word ".repeat(40))
            },
            userText = "continue",
            charBudget = 1_200,
        )
        assertTrue(fitted.system.contains("Likes short answers."))
        assertTrue(fitted.system.contains("Riyadh"))
        assertTrue(fitted.system.contains("Talked about tea."))
        assertEquals("continue", fitted.turns.last().text)
        assertTrue(fitted.estimatedTokens <= 1_200 / PromptFitter.CHARS_PER_TOKEN)
        assertTrue(fitted.turns.size < 30)
    }
}
