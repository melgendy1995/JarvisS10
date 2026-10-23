package com.jarvis.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    private var clock = 1_000L
    private val dao = FakeMemoryDao()
    private val repository = MemoryRepository(dao, now = { clock.also { clock += 1 } })

    @Test
    fun newProfileUsesTheNeutralPersonaAndSaveCapsIt() = runBlocking {
        val profile = repository.profile()
        assertEquals("Mahmoud", profile.name)
        assertEquals("en", profile.language)
        assertEquals(DefaultPersona.TEXT, profile.persona)
        repository.savePersona("x".repeat(MemoryLimits.PERSONA_CHARS + 50))
        assertEquals(MemoryLimits.PERSONA_CHARS, repository.profile().persona.length)
        assertEquals("Mahmoud", repository.profile().name)
    }

    @Test
    fun rememberUpdatesTheSameKeyAndForgetRemovesMatches() = runBlocking {
        repository.remember("tea", "black", "voice")
        repository.remember("tea", "mint", "voice")
        repository.remember("city", "Riyadh", "voice")
        assertEquals(listOf("mint"), repository.recall("tea").map { it.value })
        assertEquals(1, repository.forget("tea"))
        assertTrue(repository.recall("tea").isEmpty())
        assertEquals(listOf("city"), repository.recall("me").map { it.key })
        assertEquals(0, repository.forget("  "))
    }

    @Test
    fun theTwentyFirstTurnFoldsTheOldestIntoTheSummary() = runBlocking {
        repeat(20) { index ->
            val outcome = repository.appendTurn(TurnRole.USER, "line $index", "phone", Channels.VOICE)
            assertTrue(outcome.dropped.isEmpty())
        }
        val overflow = repository.appendTurn(TurnRole.USER, "line 20", "phone", Channels.VOICE)
        assertEquals(listOf("line 0"), overflow.dropped.map { it.text })
        assertEquals("user: line 0", overflow.localSummary)
        assertFalse(overflow.askCloud)
        val kept = repository.snapshot(Channels.VOICE).turns.map { it.text }
        assertEquals(20, kept.size)
        assertEquals("line 1", kept.first())
        assertEquals("line 20", kept.last())
    }

    @Test
    fun cloudRewriteIsRequestedEveryEighthPrunedTurnAndDoesNotClobberANewerSummary() = runBlocking {
        repeat(20) { index ->
            repository.appendTurn(TurnRole.USER, "seed $index", "phone", Channels.VOICE)
        }
        var asked: PruneOutcome? = null
        repeat(8) { index ->
            val outcome = repository.appendTurn(TurnRole.USER, "extra $index", "phone", Channels.VOICE)
            if (outcome.askCloud) asked = outcome
        }
        val request = asked ?: error("expected a cloud summary request")
        assertTrue(request.localSummary.contains("seed 0"))
        assertTrue(request.localSummary.contains("seed 7"))
        repository.appendTurn(TurnRole.USER, "newer", "phone", Channels.VOICE)
        assertFalse(repository.replaceSummary(request.summaryUpdatedAt, "stale cloud text"))
        val snapshot = repository.snapshot(Channels.VOICE)
        assertEquals("newer", snapshot.turns.last().text)
        assertTrue(snapshot.summary.contains("seed 8"))
        assertFalse(snapshot.summary.contains("stale cloud text"))
    }

    @Test
    fun duplicateTailIsNotStoredTwice() = runBlocking {
        repository.appendTurn(TurnRole.ASSISTANT, "Hello.", "gemini-text", Channels.VOICE)
        repository.appendTurn(TurnRole.ASSISTANT, "Hello.", "gemini-text", Channels.VOICE)
        assertEquals(listOf("Hello."), repository.snapshot(Channels.VOICE).turns.map { it.text })
    }

    @Test
    fun voiceAndTelegramKeepSeparateWindows() = runBlocking {
        repeat(21) { index ->
            repository.appendTurn(TurnRole.USER, "voice $index", "phone", Channels.VOICE)
        }
        repository.appendTurn(TurnRole.USER, "from telegram", "phone", Channels.TELEGRAM)
        assertEquals(20, repository.snapshot(Channels.VOICE).turns.size)
        assertEquals(listOf("from telegram"), repository.snapshot(Channels.TELEGRAM).turns.map { it.text })
    }
}
