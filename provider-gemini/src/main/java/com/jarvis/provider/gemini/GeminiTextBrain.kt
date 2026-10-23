package com.jarvis.provider.gemini

import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.jarvis.core.brain.Brain
import com.jarvis.core.brain.BrainAccent
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.PromptFitter
import com.jarvis.core.brain.Speaker
import com.jarvis.core.skills.SkillRegistry
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

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
        return try {
            gate(id, configured, online, cooldown, time)?.let { return it }
            Log.i(TAG, "text begin")
            usage.recordTextRequest()
            val spoken = withTimeoutOrNull(TEXT_TIMEOUT_MS) { complete(request) }
            if (spoken == null || spoken.text.isBlank()) {
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
            if (!currentCoroutineContext().isActive) throw cancelled
            cancelled.toBrainFailure(id, cooldown, time)
        } catch (error: Throwable) {
            error.toBrainFailure(id, cooldown, time)
        }
    }

    override suspend fun summarize(prompt: String): BrainResult {
        if (prompt.isBlank()) return BrainResult.Failure(id, BrainError.Unavailable)
        return try {
            gate(id, configured, online, cooldown, time)?.let { return it }
            usage.recordTextRequest()
            val model = GeminiModels.plain(SUMMARY_SYSTEM)
            val response = model.generateContent(listOf(content("user") { text(prompt) }))
            val text = response.text?.trim().orEmpty()
            if (text.isBlank()) {
                BrainResult.Failure(id, BrainError.Unavailable)
            } else {
                BrainResult.Answer(brainId = id, accent = accent, text = text)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error.toBrainFailure(id, cooldown, time)
        }
    }

    private suspend fun complete(request: BrainRequest): Spoken {
        val fitter = PromptFitter()
        val assembled = fitter.memorySystem(
            baseSystem = ModelConfig.SYSTEM_PROMPT,
            persona = request.persona,
            facts = request.facts,
            summary = request.summary,
            charBudget = PromptFitter.GEMINI_SYSTEM_CHARS,
        )
        val fitted = fitter.fit(assembled, request.turns, request.utterance)
        val model = GeminiModels.text(registry, fitted.system.ifBlank { ModelConfig.SYSTEM_PROMPT })
        val history = mutableListOf<Content>()
        for (turn in fitted.turns) {
            val role = if (turn.speaker == Speaker.USER) "user" else "model"
            history += content(role) { text(turn.text) }
        }
        if (history.isEmpty()) history += content("user") { text(request.utterance) }
        val utterance = request.utterance
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
        private const val TEXT_TIMEOUT_MS = 30_000L
        private const val MAX_TOOL_ROUNDS = 4
        private const val SUMMARY_SYSTEM =
            "Summarize the conversation memory in a few short sentences. " +
                "Reply with the summary only. Do not use tools."
    }
}
