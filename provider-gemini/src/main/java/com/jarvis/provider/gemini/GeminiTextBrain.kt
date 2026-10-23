package com.jarvis.provider.gemini

import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.jarvis.core.brain.Brain
import com.jarvis.core.brain.BrainAccent
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.skills.SkillRegistry
import kotlinx.coroutines.CancellationException

class GeminiTextBrain(
    private val configured: () -> Boolean,
    private val online: () -> Boolean,
    private val cooldown: GeminiCooldown,
    private val usage: UsageCounter,
    private val registry: SkillRegistry,
    private val time: TimeSource = SystemTimeSource,
) : Brain {
    override val id: String = ID
    override val accent: BrainAccent = BrainAccent.GEMINI

    override suspend fun reply(request: BrainRequest): BrainResult {
        gate(id, configured, online, cooldown, time)?.let { return it }
        return try {
            usage.recordTextRequest()
            val spoken = complete(request.utterance)
            if (spoken.text.isBlank()) {
                BrainResult.Failure(id, BrainError.Unavailable)
            } else {
                BrainResult.Answer(
                    brainId = id,
                    accent = accent,
                    text = spoken.text,
                    needsPermission = spoken.needsPermission,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error.toBrainFailure(id, cooldown, time)
        }
    }

    private suspend fun complete(utterance: String): Spoken {
        val model = GeminiModels.text(registry)
        val history = mutableListOf<Content>()
        history += content("user") { text(utterance) }
        var response = model.generateContent(history)
        val toolText = StringBuilder()
        var permission: String? = null
        var round = 0
        while (response.functionCalls.isNotEmpty() && round < MAX_TOOL_ROUNDS) {
            round++
            val calls = response.functionCalls
            history += content("model") {
                calls.forEach { part(it) }
            }
            val responses = calls.map { call ->
                val outcome = SkillTools.respond(registry, utterance, call)
                Transcripts.append(toolText, outcome.spoken)
                if (permission == null) permission = outcome.needsPermission
                outcome.part
            }
            history += content("user") {
                responses.forEach { part(it) }
            }
            response = model.generateContent(history)
        }
        val text = response.text?.takeIf { it.isNotBlank() } ?: toolText.toString()
        return Spoken(text, permission)
    }

    private data class Spoken(val text: String, val needsPermission: String?)

    companion object {
        const val ID = "gemini-text"
        private const val MAX_TOOL_ROUNDS = 4
    }
}
