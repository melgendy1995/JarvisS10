package com.jarvis.provider.groq

import com.jarvis.core.brain.Brain
import com.jarvis.core.brain.BrainAccent
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.FittedPrompt
import com.jarvis.core.brain.PromptFitter
import com.jarvis.core.brain.Speaker
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class GroqBrain(
    private val proxyUrl: () -> String,
    private val bearer: () -> String,
    private val online: () -> Boolean,
    private val rotation: GroqRotation,
    private val registry: SkillRegistry,
    private val transport: GroqTransport,
    private val clock: GroqClock = SystemGroqClock,
    private val fitter: PromptFitter = PromptFitter(),
    private val systemPrompt: String = GroqModels.SYSTEM,
    private val log: (String) -> Unit = {},
) : Brain {
    override val id: String = ID
    override val accent: BrainAccent = BrainAccent.GROQ

    fun configured(): Boolean {
        return GroqProxy.normalize(proxyUrl()) != null && bearer().trim().isNotEmpty()
    }

    /** True when a chat model can take a turn: proxy, bearer, and a model under its cap. */
    fun hasCapacity(): Boolean = configured() && rotation.hasAvailableModel()

    override suspend fun reply(request: BrainRequest): BrainResult {
        if (!configured()) return BrainResult.Failure(id, BrainError.NotConfigured)
        if (!online()) return BrainResult.Failure(id, BrainError.Unavailable)
        val models = rotation.models()
        if (models.isEmpty()) return BrainResult.Failure(id, BrainError.NotConfigured)
        val start = rotation.peekIndex()
        var limited = false
        for (offset in models.indices) {
            val index = (start + offset) % models.size
            val model = models[index]
            if (!rotation.isAvailable(model)) continue
            val fitted = fitter.fit(systemFor(request), request.turns, request.utterance)
            log("prompt ~${fitted.estimatedTokens} tokens model=$model")
            when (val outcome = complete(model, request.utterance, fitted)) {
                is Turn.Spoken -> {
                    rotation.recordTokens(model, outcome.tokens)
                    rotation.commitIndex((index + 1) % models.size)
                    return BrainResult.Answer(
                        brainId = id,
                        accent = accent,
                        text = outcome.text,
                        needsPermission = outcome.needsPermission,
                    )
                }
                is Turn.Limited -> {
                    limited = true
                    rotation.coolUntil(model, cooldownUntil(outcome))
                }
                Turn.NotConfigured -> return BrainResult.Failure(id, BrainError.NotConfigured)
                Turn.Unavailable -> return BrainResult.Failure(id, BrainError.Unavailable)
            }
        }
        if (limited || !rotation.hasAvailableModel()) {
            val wait = rotation.soonestWait()
            return BrainResult.Failure(
                id,
                BrainError.RateLimited(wait, isDaily = wait >= Duration.ofHours(1)),
            )
        }
        return BrainResult.Failure(id, BrainError.Unavailable)
    }

    private fun systemFor(request: BrainRequest): String {
        val persona = request.persona.trim()
        if (persona.isEmpty()) return systemPrompt
        return systemPrompt + "\n\n" + persona
    }

    private fun cooldownUntil(limited: Turn.Limited): Instant {
        val wait = when {
            limited.retryAfter != null && limited.retryAfter > Duration.ZERO -> limited.retryAfter
            limited.daily -> Duration.ofHours(24)
            else -> Duration.ofSeconds(GroqModels.TPM_COOLDOWN_SECONDS)
        }
        return clock.now().plus(wait)
    }

    private suspend fun complete(model: String, utterance: String, fitted: FittedPrompt): Turn {
        val tools = registry.groqTools()
        val messages = ArrayList<OutgoingMessage>()
        if (fitted.system.isNotEmpty()) messages += OutgoingMessage(role = "system", content = fitted.system)
        for (turn in fitted.turns) {
            val role = if (turn.speaker == Speaker.USER) "user" else "assistant"
            messages += OutgoingMessage(role = role, content = turn.text)
        }
        var spent = 0
        var permission: String? = null
        val toolText = StringBuilder()
        for (round in 0..MAX_TOOL_ROUNDS) {
            val result = try {
                transport.complete(GroqChatRequest(model, messages.toList(), tools))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return Turn.Unavailable
            }
            when (result) {
                is GroqChatResult.Limited -> return Turn.Limited(result.retryAfter, result.daily)
                GroqChatResult.NotConfigured -> return Turn.NotConfigured
                GroqChatResult.Unavailable -> return Turn.Unavailable
                is GroqChatResult.Ok -> {
                    spent += result.totalTokens ?: 0
                    val calls = result.message.toolCalls
                    if (calls.isEmpty() || round == MAX_TOOL_ROUNDS) {
                        val text = result.message.content?.trim().orEmpty().ifBlank { toolText.toString().trim() }
                        if (text.isEmpty()) return Turn.Unavailable
                        if (spent == 0) spent = fitted.estimatedTokens.coerceAtLeast(1)
                        return Turn.Spoken(text, spent, permission)
                    }
                    messages += OutgoingMessage(
                        role = "assistant",
                        content = result.message.content,
                        toolCalls = calls,
                    )
                    for (call in calls) {
                        val (spoken, needed) = runTool(utterance, call)
                        if (permission == null) permission = needed
                        if (spoken.isNotBlank()) {
                            if (toolText.isNotEmpty()) toolText.append(' ')
                            toolText.append(spoken)
                        }
                        messages += OutgoingMessage(
                            role = "tool",
                            content = spoken,
                            toolCallId = call.id,
                        )
                    }
                }
            }
        }
        return Turn.Unavailable
    }

    private suspend fun runTool(utterance: String, call: ToolCallSpec): Pair<String, String?> {
        val skill = registry.registered().firstOrNull { it.id == call.name }
        val slots = parseSlots(call.arguments)
        if ("millis" !in slots && "duration_ms" in slots) {
            slots["millis"] = slots.getValue("duration_ms")
        }
        val result = when (skill) {
            null -> SkillResult("I don't have that tool.", success = false)
            else -> try {
                skill.run(SkillCall(skillId = skill.id, utterance = utterance, slots = slots))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                SkillResult("That didn't work.", success = false)
            }
        }
        return result.text to result.needsPermission
    }

    private fun parseSlots(arguments: String): MutableMap<String, String> {
        val element = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments)
        }.getOrNull() as? JsonObject ?: return linkedMapOf()
        val slots = LinkedHashMap<String, String>()
        for ((key, value) in element) {
            slots[key] = when (value) {
                is kotlinx.serialization.json.JsonNull -> ""
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                else -> value.toString()
            }
        }
        return slots
    }

    private sealed interface Turn {
        data class Spoken(
            val text: String,
            val tokens: Int,
            val needsPermission: String?,
        ) : Turn

        data class Limited(val retryAfter: Duration?, val daily: Boolean) : Turn
        data object NotConfigured : Turn
        data object Unavailable : Turn
    }

    companion object {
        const val ID = "groq"
        private const val MAX_TOOL_ROUNDS = 4
    }
}
