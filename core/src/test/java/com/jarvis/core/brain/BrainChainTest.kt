package com.jarvis.core.brain

import com.jarvis.core.CommandRouter
import com.jarvis.core.skills.Skill
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainChainTest {
    private val registry = SkillRegistry().apply {
        register(object : Skill {
            override val id = "time"
            override fun match(text: String): SkillCall? {
                return if (text.trim() == "what time is it") SkillCall(id, text) else null
            }
            override fun geminiDeclaration(): Any? = null
            override fun groqTool(): Any? = null
            override suspend fun run(call: SkillCall) = SkillResult("It's 3:04 PM.")
        })
    }
    private val router = CommandRouter(registry)

    @Test
    fun routerHitDoesNotCallBrains() = runBlocking {
        val live = fake("gemini-live") { error("live should not run") }
        val chain = BrainChain(router, listOf(live))
        val result = chain.reply(BrainRequest("what time is it")) as BrainResult.Answer
        assertEquals("phone", result.brainId)
        assertEquals(BrainAccent.PHONE, result.accent)
        assertEquals("It's 3:04 PM.", result.text)
        assertEquals(0, live.calls)
    }

    @Test
    fun blankUtteranceStaysOnThePhone() = runBlocking {
        val live = fake("gemini-live") { error("live should not run") }
        val result = BrainChain(router, listOf(live)).reply(BrainRequest("  ")) as BrainResult.Answer
        assertEquals("I didn't catch that.", result.text)
        assertEquals(0, live.calls)
    }

    @Test
    fun missTriesLiveThenStops() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Answer("gemini-live", BrainAccent.GEMINI, "A joke.")
        }
        val text = fake("gemini-text") { error("text should not run") }
        val result = BrainChain(router, listOf(live, text)).reply(BrainRequest("tell me a joke"))
        assertEquals("A joke.", (result as BrainResult.Answer).text)
        assertEquals(BrainAccent.GEMINI, result.accent)
        assertEquals(1, live.calls)
        assertEquals(0, text.calls)
    }

    @Test
    fun rateLimitUnavailableAndNotConfiguredFallThrough() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Failure(
                "gemini-live",
                BrainError.RateLimited(Duration.ofHours(4), isDaily = true),
            )
        }
        val text = fake("gemini-text") {
            BrainResult.Failure("gemini-text", BrainError.Unavailable)
        }
        val later = fake("groq") {
            BrainResult.Answer("groq", BrainAccent.GROQ, "From the next brain.")
        }
        val result = BrainChain(router, listOf(live, text, later)).reply(BrainRequest("tell me a joke"))
        assertEquals("From the next brain.", (result as BrainResult.Answer).text)
        assertEquals(1, live.calls)
        assertEquals(1, text.calls)
        assertEquals(1, later.calls)
    }

    @Test
    fun notConfiguredFallsThroughToText() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Failure("gemini-live", BrainError.NotConfigured)
        }
        val text = fake("gemini-text") {
            BrainResult.Answer("gemini-text", BrainAccent.GEMINI, "Text answer.")
        }
        val result = BrainChain(router, listOf(live, text)).reply(BrainRequest("tell me a joke"))
        assertEquals("Text answer.", (result as BrainResult.Answer).text)
    }

    @Test
    fun childCancellationWhileCallerIsActiveFallsThrough() = runBlocking {
        val live = fake("gemini-live") { throw kotlinx.coroutines.CancellationException("channel") }
        val text = fake("gemini-text") {
            BrainResult.Answer("gemini-text", BrainAccent.GEMINI, "Recovered.")
        }
        val result = BrainChain(router, listOf(live, text)).reply(BrainRequest("tell me a joke"))
        assertEquals("Recovered.", (result as BrainResult.Answer).text)
        assertEquals(1, text.calls)
    }

    @Test
    fun thrownBrainBecomesUnavailableAndTheNextRuns() = runBlocking {
        val live = fake("gemini-live") { throw IllegalStateException("socket") }
        val text = fake("gemini-text") {
            BrainResult.Answer("gemini-text", BrainAccent.GEMINI, "Recovered.")
        }
        val result = BrainChain(router, listOf(live, text)).reply(BrainRequest("tell me a joke"))
        assertEquals("Recovered.", (result as BrainResult.Answer).text)
    }

    @Test
    fun everyBrainFailingSpeaksAnOfflineLine() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Failure("gemini-live", BrainError.Unavailable)
        }
        val text = fake("gemini-text") {
            BrainResult.Failure("gemini-text", BrainError.NotConfigured)
        }
        val result = BrainChain(router, listOf(live, text)).reply(BrainRequest("tell me a joke"))
        val failure = result as BrainResult.Failure
        assertEquals("offline", failure.brainId)
        assertEquals(BrainError.NotConfigured, failure.error)
        assertTrue(failure.text.contains("isn't set up", ignoreCase = true))
    }

    @Test
    fun geminiFailureHandsTheSameTurnsToTheNextBrain() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Failure(
                "gemini-live",
                BrainError.RateLimited(Duration.ofHours(4), isDaily = true),
            )
        }
        val text = fake("gemini-text") {
            BrainResult.Failure("gemini-text", BrainError.Unavailable)
        }
        val groq = fake("groq") { request ->
            BrainResult.Answer("groq", BrainAccent.GROQ, "Continuing: ${request.turns.size}")
        }
        val request = BrainRequest(
            utterance = "and the other one?",
            turns = listOf(
                ConversationTurn(Speaker.USER, "tell me a joke"),
                ConversationTurn(Speaker.ASSISTANT, "A short joke."),
            ),
            persona = "Likes short answers.",
        )
        val result = BrainChain(router, listOf(live, text, groq)).reply(request) as BrainResult.Answer
        assertEquals("Continuing: 2", result.text)
        assertEquals(BrainAccent.GROQ, result.accent)
        assertEquals(request, groq.lastRequest)
    }

    @Test
    fun dailyRateLimitIsTheMessageWhenItIsTheLastError() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Failure(
                "gemini-live",
                BrainError.RateLimited(Duration.ofHours(2), isDaily = true),
            )
        }
        val result = BrainChain(router, listOf(live)).reply(BrainRequest("tell me a joke"))
        val failure = result as BrainResult.Failure
        assertTrue(failure.error is BrainError.RateLimited)
        assertTrue((failure.error as BrainError.RateLimited).isDaily)
        assertTrue(failure.text.contains("cooling down", ignoreCase = true))
    }

    private fun fake(id: String, handler: suspend (BrainRequest) -> BrainResult): FakeBrain {
        return FakeBrain(id, handler)
    }

    private class FakeBrain(
        override val id: String,
        private val handler: suspend (BrainRequest) -> BrainResult,
    ) : Brain {
        override val accent = BrainAccent.GEMINI
        var calls = 0
        var lastRequest: BrainRequest? = null
        override suspend fun reply(request: BrainRequest): BrainResult {
            calls++
            lastRequest = request
            return handler(request)
        }
    }
}
