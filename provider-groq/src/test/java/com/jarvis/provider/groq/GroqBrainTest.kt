package com.jarvis.provider.groq

import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.JarvisPrompt
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.ConversationTurn
import com.jarvis.core.brain.MemoryFact
import com.jarvis.core.brain.PromptFitter
import com.jarvis.core.brain.Speaker
import com.jarvis.core.skills.Skill
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqBrainTest {
    private val start = Instant.parse("2026-10-23T18:00:00Z")
    private val clock = ManualClock(start)
    private val store = MemoryGroqStore()
    private val rotation = GroqRotation(clock, store)
    private val transport = ScriptedTransport()
    private val logs = ArrayList<String>()
    private val registry = SkillRegistry()

    private val brain = GroqBrain(
        proxyUrl = { "https://jarvis.example.workers.dev" },
        bearer = { "worker-bearer" },
        online = { true },
        rotation = rotation,
        registry = registry,
        transport = transport,
        clock = clock,
        log = { logs += it },
    )

    @Test
    fun rateLimitCoolsOnlyThatModelAndTheNextOneAnswers() = runBlocking {
        transport.handler = { request ->
            if (request.model == GroqModels.CHAT[0]) {
                GroqChatResult.Limited(retryAfter = Duration.ofSeconds(120), daily = false)
            } else {
                GroqChatResult.Ok(AssistantMessage("From the second model.", emptyList()), totalTokens = 30)
            }
        }
        val result = brain.reply(BrainRequest("tell me a joke")) as BrainResult.Answer
        assertEquals("From the second model.", result.text)
        assertEquals(listOf(GroqModels.CHAT[0], GroqModels.CHAT[1]), transport.chats.map { it.model })
        assertFalse(rotation.isAvailable(GroqModels.CHAT[0]))
        assertTrue(rotation.isAvailable(GroqModels.CHAT[2]))
        clock.instant = start.plusSeconds(119)
        assertFalse(rotation.isAvailable(GroqModels.CHAT[0]))
        clock.instant = start.plusSeconds(120)
        assertTrue(rotation.isAvailable(GroqModels.CHAT[0]))
        assertEquals(2, rotation.peekIndex())
        assertEquals(30, rotation.tokensUsed(GroqModels.CHAT[1]))
    }

    @Test
    fun dailyWordingWithoutRetryAfterCoolsForADay() = runBlocking {
        transport.handler = {
            GroqChatResult.Limited(retryAfter = null, daily = true)
        }
        val result = brain.reply(BrainRequest("hello")) as BrainResult.Failure
        val error = result.error as BrainError.RateLimited
        assertTrue(error.isDaily)
        assertEquals(Duration.ofHours(24), error.retryAfter)
        assertFalse(rotation.isAvailable(GroqModels.CHAT[0]))
        assertFalse(rotation.isAvailable(GroqModels.CHAT[1]))
        assertFalse(rotation.isAvailable(GroqModels.CHAT[2]))
        assertTrue(transport.chats.size == 3)
    }

    @Test
    fun aCappedModelIsSkippedAndNotCalled() = runBlocking {
        rotation.recordTokens(GroqModels.CHAT[0], GroqModels.DAILY_TOKEN_CAP)
        transport.handler = { request ->
            GroqChatResult.Ok(AssistantMessage("ok ${request.model}", emptyList()), totalTokens = 10)
        }
        val result = brain.reply(BrainRequest("hello")) as BrainResult.Answer
        assertEquals("ok ${GroqModels.CHAT[1]}", result.text)
        assertEquals(listOf(GroqModels.CHAT[1]), transport.chats.map { it.model })
        assertEquals(GroqModels.DAILY_TOKEN_CAP, rotation.tokensUsed(GroqModels.CHAT[0]))
        assertEquals(10, rotation.tokensUsed(GroqModels.CHAT[1]))
    }

    @Test
    fun missingProxyOrGroqHostIsNotConfigured() = runBlocking {
        val empty = GroqBrain(
            proxyUrl = { "" },
            bearer = { "worker-bearer" },
            online = { true },
            rotation = rotation,
            registry = registry,
            transport = transport,
            clock = clock,
        )
        val direct = GroqBrain(
            proxyUrl = { "https://api.groq.com/openai/v1" },
            bearer = { "gsk_should_not_be_sent" },
            online = { true },
            rotation = rotation,
            registry = registry,
            transport = transport,
            clock = clock,
        )
        assertTrue(empty.reply(BrainRequest("hi")).errorOrNull() is BrainError.NotConfigured)
        assertTrue(direct.reply(BrainRequest("hi")).errorOrNull() is BrainError.NotConfigured)
        assertTrue(transport.chats.isEmpty())
        assertNull(GroqProxy.normalize("https://api.groq.com"))
        assertEquals("https://jarvis.example.workers.dev", GroqProxy.normalize("https://jarvis.example.workers.dev/"))
    }

    @Test
    fun handoffSendsPriorTurnsAndStaysInsideTheTokenBudget() = runBlocking {
        transport.handler = {
            GroqChatResult.Ok(AssistantMessage("The second one.", emptyList()), totalTokens = 40)
        }
        val request = BrainRequest(
            utterance = "and the other one?",
            turns = listOf(
                ConversationTurn(Speaker.USER, "tell me a joke"),
                ConversationTurn(Speaker.ASSISTANT, "A short joke."),
            ),
            persona = "Mahmoud likes short answers.",
        )
        val result = brain.reply(request) as BrainResult.Answer
        assertEquals("The second one.", result.text)
        val sent = transport.chats.single().messages
        assertTrue(sent.first().content!!.contains("Mahmoud likes short answers."))
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.role })
        assertEquals("tell me a joke", sent[1].content)
        assertEquals("A short joke.", sent[2].content)
        assertEquals("and the other one?", sent[3].content)
        val prompt = logs.single { it.contains("prompt ~") }
        val tokens = Regex("""prompt ~(\d+) tokens""").find(prompt)!!.groupValues[1].toInt()
        assertTrue(tokens <= PromptFitter.TOKEN_BUDGET)
    }

    @Test
    fun systemMessageCarriesFactsAndSummary() = runBlocking {
        transport.handler = {
            GroqChatResult.Ok(AssistantMessage("Noted.", emptyList()), totalTokens = 12)
        }
        val result = brain.reply(
            BrainRequest(
                utterance = "what next",
                persona = "Lives in Riyadh.",
                facts = listOf(MemoryFact("tea", "mint", pinned = true)),
                summary = "Talked about the trip.",
            ),
        ) as BrainResult.Answer
        assertEquals("Noted.", result.text)
        val system = transport.chats.single().messages.first()
        val body = system.content.orEmpty()
        assertEquals("system", system.role)
        assertTrue(body.startsWith(JarvisPrompt.SYSTEM))
        assertTrue(body.contains("general-knowledge"))
        assertFalse(body.contains("voice assistant on a phone"))
        assertTrue(body.contains("Lives in Riyadh."))
        assertTrue(body.contains("tea: mint"))
        assertTrue(body.contains("Talked about the trip."))
    }

    @Test
    fun summarizeAsksForTextWithoutTools() = runBlocking {
        transport.handler = {
            GroqChatResult.Ok(AssistantMessage("A short memory.", emptyList()), totalTokens = 9)
        }
        val result = brain.summarize("Previous summary: tea.") as BrainResult.Answer
        assertEquals("A short memory.", result.text)
        val request = transport.chats.single()
        assertTrue(request.tools.isEmpty())
        assertTrue(request.messages.any { it.role == "user" && it.content!!.contains("tea") })
        assertEquals(9, rotation.tokensUsed(GroqModels.CHAT[0]))
    }

    @Test
    fun toolCallRunsTheSkillAndReturnsItsWords() = runBlocking {
        val skill = EchoSkill()
        registry.register(skill)
        transport.handler = { request ->
            val last = request.messages.last()
            if (last.role == "tool") {
                GroqChatResult.Ok(AssistantMessage("It's 3:04 PM.", emptyList()), totalTokens = 8)
            } else {
                GroqChatResult.Ok(
                    AssistantMessage(
                        content = null,
                        toolCalls = listOf(ToolCallSpec("call_1", "time", """{"kind":"time","duration_ms":1000}""")),
                    ),
                    totalTokens = 20,
                )
            }
        }
        val result = brain.reply(BrainRequest("what time is it")) as BrainResult.Answer
        assertEquals("It's 3:04 PM.", result.text)
        assertEquals(1, skill.calls)
        assertEquals("1000", skill.lastSlots["millis"])
        val followUp = transport.chats[1].messages
        assertEquals("tool", followUp.last().role)
        assertEquals("call_1", followUp.last().toolCallId)
        assertEquals("It's 3:04 PM.", followUp.last().content)
        assertEquals(28, rotation.tokensUsed(GroqModels.CHAT[0]))
    }

    private fun BrainResult.errorOrNull(): BrainError? = (this as? BrainResult.Failure)?.error

    private class EchoSkill : Skill {
        var calls = 0
        var lastSlots: Map<String, String> = emptyMap()
        override val id = "time"
        override fun match(text: String): SkillCall? = null
        override fun geminiDeclaration(): Any? = null
        override fun groqTool(): Any = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "time",
                "description" to "Get the time",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("kind" to mapOf("type" to "string")),
                    "required" to listOf("kind"),
                ),
            ),
        )
        override suspend fun run(call: SkillCall): SkillResult {
            calls++
            lastSlots = call.slots
            return SkillResult("It's 3:04 PM.")
        }
    }
}

class ScriptedTransport : GroqTransport {
    val chats = mutableListOf<GroqChatRequest>()
    var handler: (GroqChatRequest) -> GroqChatResult = { GroqChatResult.Unavailable }
    override suspend fun complete(request: GroqChatRequest): GroqChatResult {
        chats += request
        return handler(request)
    }
    override suspend fun transcribe(wav: ByteArray): GroqTranscript = GroqTranscript.Failed
}
