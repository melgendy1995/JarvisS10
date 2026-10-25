package com.jarvis.core.brain

import com.jarvis.core.CommandRouter
import com.jarvis.core.skills.Skill
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
    fun staleAnswerTriggersOneForcedSearchRetry() = runBlocking {
        val stale = fake("gemini-text") { request ->
            if (request.forceLiveSearch) {
                BrainResult.Failure("gemini-text", BrainError.Unavailable)
            }
            BrainResult.Answer(
                "gemini-text",
                BrainAccent.GEMINI,
                "I don't have access to real-time information.",
            )
        }
        val groq = fake("groq") { request ->
            assertTrue(request.forceLiveSearch)
            BrainResult.Answer("groq", BrainAccent.GROQ, "Saudi Arabia won 3-1.")
        }
        val result = BrainChain(router, listOf(stale, groq)).reply(BrainRequest("who won the match"))
        assertEquals("Saudi Arabia won 3-1.", (result as BrainResult.Answer).text)
        assertEquals(1, stale.calls)
        assertEquals(1, groq.calls)
    }

    @Test
    fun groundedOrPlainAnswersDoNotRetry() = runBlocking {
        val live = fake("gemini-live") {
            BrainResult.Answer("gemini-live", BrainAccent.GEMINI, "Live answer.", search = null)
        }
        val groq = fake("groq") { error("groq should not run") }
        val plain = BrainChain(router, listOf(live, groq)).reply(BrainRequest("tell me a joke"))
        assertEquals("Live answer.", (plain as BrainResult.Answer).text)
        assertEquals(1, live.calls)
        assertEquals(0, groq.calls)
    }

    @Test
    fun failedRetryKeepsTheFirstAnswer() = runBlocking {
        val stale = fake("gemini-text") {
            BrainResult.Answer(
                "gemini-text",
                BrainAccent.GEMINI,
                "I do not have access to live data.",
            )
        }
        val groq = fake("groq") { BrainResult.Failure("groq", BrainError.Unavailable) }
        val result = BrainChain(router, listOf(stale, groq)).reply(BrainRequest("who won the match"))
        assertEquals(
            "I do not have access to live data.",
            (result as BrainResult.Answer).text,
        )
        assertEquals(1, stale.calls)
        assertEquals(1, groq.calls)
    }

    @Test
    fun retryFallsThroughBrainsInTheSecondPass() = runBlocking {
        val stale = fake("gemini-text") { request ->
            if (request.forceLiveSearch) {
                BrainResult.Failure("gemini-text", BrainError.NotConfigured)
            } else {
                BrainResult.Answer(
                    "gemini-text",
                    BrainAccent.GEMINI,
                    "As of my last knowledge, nothing.",
                )
            }
        }
        val groq = fake("groq") { request ->
            assertTrue(request.forceLiveSearch)
            BrainResult.Answer("groq", BrainAccent.GROQ, "From search.")
        }
        val result = BrainChain(router, listOf(stale, groq)).reply(BrainRequest("who won the match"))
        assertEquals("From search.", (result as BrainResult.Answer).text)
    }

    @Test
    fun parentCancelSkipsTheRestOfTheChain() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val live = fake("gemini-live") {
            started.complete(Unit)
            suspendCancellableCoroutine { }
        }
        val text = fake("gemini-text") {
            BrainResult.Answer("gemini-text", BrainAccent.GEMINI, "nope")
        }
        val job = launch {
            BrainChain(router, listOf(live, text)).reply(BrainRequest("tell me a joke"))
        }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertEquals(0, text.calls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun logsEachStepWithBrainIdAndElapsed() = runBlocking {
        val lines = mutableListOf<String>()
        val live = fake("gemini-live") {
            BrainResult.Failure("gemini-live", BrainError.Unavailable)
        }
        val text = fake("gemini-text") {
            BrainResult.Answer("gemini-text", BrainAccent.GEMINI, "Hi")
        }
        val result = BrainChain(router, listOf(live, text), lines::add)
            .reply(BrainRequest("tell me a joke")) as BrainResult.Answer
        assertEquals("Hi", result.text)
        assertTrue(lines.any { it.startsWith("brain=gemini-live outcome=failure:Unavailable elapsedMs=") })
        assertTrue(lines.any { it.startsWith("brain=gemini-text outcome=answer elapsedMs=") })
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

    @Test
    fun aClaimedPhoneActionIsReroutedToTheLocalSkill() = runBlocking {
        val skillRouter = CommandRouter(
            SkillRegistry().apply {
                register(object : Skill {
                    override val id = "app_control"
                    override fun match(text: String): SkillCall? {
                        return if (text.trim() in setOf("pause music", "stop music")) {
                            SkillCall(id, text)
                        } else {
                            null
                        }
                    }

                    override fun geminiDeclaration(): Any? = null
                    override fun groqTool(): Any? = null
                    override suspend fun run(call: SkillCall) = SkillResult("Paused.")
                })
            },
        )
        val groq = fake("groq") {
            BrainResult.Answer("groq", BrainAccent.GROQ, "I have stopped the music.")
        }
        val result = BrainChain(skillRouter, listOf(groq)).reply(BrainRequest("no no no")) as BrainResult.Answer
        assertEquals("Paused.", result.text)
        assertEquals(BrainAccent.PHONE, result.accent)
    }

    @Test
    fun aClaimWithoutACommandBecomesHonest() = runBlocking {
        val groq = fake("groq") {
            BrainResult.Answer("groq", BrainAccent.GROQ, "Shuffle mode is now active on YouTube Music.")
        }
        val result = BrainChain(router, listOf(groq)).reply(BrainRequest("shuffle")) as BrainResult.Answer
        assertEquals("I couldn't do that. Try saying 'pause YouTube Music'.", result.text)
        assertEquals("groq", result.brainId)
    }

    @Test
    fun theClaimVerbRerouteRunsBeforeTheHonestFallback() = runBlocking {
        val skillRouter = CommandRouter(
            SkillRegistry().apply {
                register(object : Skill {
                    override val id = "app_control"
                    override fun match(text: String): SkillCall? {
                        return if (text.trim() == "stop music") SkillCall(id, text) else null
                    }

                    override fun geminiDeclaration(): Any? = null
                    override fun groqTool(): Any? = null
                    override suspend fun run(call: SkillCall) = SkillResult("تم الإيقاف.")
                })
            },
        )
        val groq = fake("groq") {
            BrainResult.Answer("groq", BrainAccent.GROQ, "I have paused the media for you, Mahmoud.")
        }
        val lines = mutableListOf<String>()
        val result = BrainChain(skillRouter, listOf(groq), lines::add)
            .reply(BrainRequest("Still not stop")) as BrainResult.Answer
        assertEquals("تم الإيقاف.", result.text)
        assertTrue(lines.any { it.startsWith("guard claim=media_stop action=routed") })
    }

    @Test
    fun aBrainThatRanItsToolKeepsItsAnswer() = runBlocking {
        val groq = fake("groq") {
            BrainResult.Answer("groq", BrainAccent.GROQ, "I have paused the media.", toolRan = true)
        }
        val result = BrainChain(router, listOf(groq)).reply(BrainRequest("no no no")) as BrainResult.Answer
        assertEquals("I have paused the media.", result.text)
    }

    @Test
    fun aGuardErrorFallsBackToTheUnguardedReply() = runBlocking {
        // A guard crash (an Error, like ART's UnsupportedFlags
        // ExceptionInInitializerError) never breaks the answer itself.
        val crashingRouter = CommandRouter(
            SkillRegistry().apply {
                register(object : Skill {
                    private var calls = 0
                    override val id = "exploding"
                    override fun match(text: String): SkillCall? {
                        calls++
                        // First call: the plain route in reply. Second call: the
                        // guard reroute must not let this escape.
                        if (calls >= 2) throw AssertionError("guard blew up")
                        return null
                    }
                    override fun geminiDeclaration(): Any? = null
                    override fun groqTool(): Any? = null
                    override suspend fun run(call: SkillCall) = SkillResult("unused")
                })
            },
        )
        val groq = fake("groq") {
            BrainResult.Answer("groq", BrainAccent.GROQ, "The timer is now running.")
        }
        val lines = mutableListOf<String>()
        val result = BrainChain(crashingRouter, listOf(groq), lines::add)
            .reply(BrainRequest("some question")) as BrainResult.Answer
        assertEquals("The timer is now running.", result.text)
        assertTrue(lines.any { it.startsWith("guard error AssertionError") })
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
