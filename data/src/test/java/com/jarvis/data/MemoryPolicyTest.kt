package com.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {
    @Test
    fun pruneKeepsTheNewestTurnsAndReturnsTheOlderOnes() {
        val turns = (1..25).map { "t$it" }
        val decision = TurnWindow.prune(turns, keep = 20)
        assertEquals((6..25).map { "t$it" }, decision.keep)
        assertEquals((1..5).map { "t$it" }, decision.dropped)
    }

    @Test
    fun pruneDoesNothingUntilTheWindowIsFull() {
        val turns = (1..20).map { "t$it" }
        val decision = TurnWindow.prune(turns)
        assertEquals(turns, decision.keep)
        assertTrue(decision.dropped.isEmpty())
    }

    @Test
    fun localSummaryKeepsTheNewestTail() {
        val text = SummaryText.truncate(
            previous = "OLD ".repeat(50),
            lines = listOf("user: newest fact"),
            maxChars = 40,
        )
        assertTrue(text.length <= 40)
        assertTrue(text.contains("newest fact"))
        assertFalse(text.startsWith("OLD"))
    }

    @Test
    fun localSummaryJoinsWhenItFits() {
        val text = SummaryText.truncate("Talked about tea.", listOf("user: mint"))
        assertEquals("Talked about tea. user: mint", text)
    }

    @Test
    fun pinnedFactsOutrankNewerUnpinnedOnes() {
        val facts = listOf(
            fact(id = 1, key = "city", value = "Jeddah", pinned = false, createdAt = 30),
            fact(id = 2, key = "tea", value = "mint", pinned = true, createdAt = 10),
            fact(id = 3, key = "desk", value = "window", pinned = false, createdAt = 20),
        )
        val top = FactRank.top(facts, limit = 2)
        assertEquals(listOf("tea", "city"), top.map { it.key })
    }

    @Test
    fun recallMatchesKeyOrValueAndMeMeansEverything() {
        val facts = listOf(
            fact(id = 1, key = "tea", value = "mint", pinned = false, createdAt = 1),
            fact(id = 2, key = "city", value = "Riyadh", pinned = false, createdAt = 2),
        )
        assertEquals(listOf("tea"), FactMatch.matching(facts, "mint").map { it.key })
        assertEquals(listOf("tea"), FactMatch.matching(facts, "the tea").map { it.key })
        assertEquals(listOf("tea"), FactMatch.matching(facts, "my tea").map { it.key })
        assertEquals(2, FactMatch.matching(facts, "me").size)
        assertTrue(FactMatch.matching(facts, "the").isEmpty())
        assertTrue(FactMatch.isAll("everything"))
        assertFalse(FactMatch.isAll("tea"))
    }

    private fun fact(id: Long, key: String, value: String, pinned: Boolean, createdAt: Long) = Fact(
        id = id,
        key = key,
        value = value,
        source = "voice",
        createdAt = createdAt,
        pinned = pinned,
    )
}
