package com.jarvis.provider.gemini

import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.jarvis.core.JarvisLog
import com.jarvis.core.brain.AnswerLanguage
import com.jarvis.core.brain.Brain
import com.jarvis.core.brain.BrainAccent
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.Freshness
import com.jarvis.core.brain.PromptFitter
import com.jarvis.core.brain.SearchDisplay
import com.jarvis.core.brain.Speaker
import com.jarvis.core.skills.SkillRegistry
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
        if (request.forceLiveSearch || Freshness.needsLiveInfo(request.utterance)) {
            if (usage.tryAcquireGrounded()) {
                return completeGrounded(request)
            }
            val used = usage.snapshot().groundedRequests
            JarvisLog.i(
                SEARCH,
                "cap exhausted count=$used cap=${ModelConfig.DAILY_GROUNDED_CAP} fallback=normal",
            )
        }
        val models = listOf(ModelConfig.TEXT_MODEL, ModelConfig.TEXT_MODEL_FALLBACK).distinct()
        var last: BrainResult.Failure = BrainResult.Failure(id, BrainError.Unavailable)
        for ((index, model) in models.withIndex()) {
            val outcome = completeWithin(request, model)
            if (outcome is BrainResult.Answer) return outcome
            last = outcome as BrainResult.Failure
            val rejected = lastModelRejected
            lastModelRejected = false
            if (!rejected || index == models.lastIndex) return last
            Log.w(TAG, "text model rejected model=$model fallback=${models[index + 1]}")
        }
        return last
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

    /**
     * One text call. Timeout, a blank body, or any error becomes [BrainResult.Failure]
     * so the chain can continue. A parent cancel still propagates.
     * SDK socket cancellation is wrapped so it does not cancel the voice job.
     */
    private suspend fun completeWithin(request: BrainRequest, model: String): BrainResult {
        Log.i(TAG, "text begin model=$model backend=${GeminiModels.BACKEND}")
        return try {
            usage.recordTextRequest()
            val spoken = withTimeout(TEXT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    try {
                        complete(request, model)
                    } catch (cancelled: CancellationException) {
                        if (cancelled is TimeoutCancellationException) throw cancelled
                        if (!currentCoroutineContext().isActive) throw cancelled
                        throw TextCancelled(cancelled)
                    }
                }
            }
            val text = spoken.text.trim()
            if (text.isEmpty()) {
                Log.w(TAG, "text blank model=$model")
                BrainResult.Failure(id, BrainError.Unavailable)
            } else {
                Log.i(TAG, "text result model=$model chars=${text.length} preview=${preview(text)}")
                BrainResult.Answer(
                    brainId = id,
                    accent = accent,
                    text = text,
                    needsPermission = spoken.needsPermission,
                    search = spoken.search,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "text timeout model=$model ms=$TEXT_TIMEOUT_MS", timeout)
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (wrapped: TextCancelled) {
            val cause = wrapped.cause ?: wrapped
            Log.w(TAG, "text cancelled model=$model ${cause.causeChain()}", cause)
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            Log.w(TAG, "text cancelled model=$model ${cancelled.causeChain()}", cancelled)
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (error: Throwable) {
            lastModelRejected = error.isModelRejected()
            if (lastModelRejected) {
                Log.w(TAG, "text model not found model=$model ${error.causeChain()}", error)
            }
            error.toBrainFailure(id, cooldown, time)
        }
    }

    /**
     * Grounded calls. A failure stays a failure so the chain can try Groq browser
     * search and the Worker search, instead of answering news from memory.
     * The second model runs only when the first id is rejected, not after a 429.
     */
    private suspend fun completeGrounded(request: BrainRequest): BrainResult {
        val models = listOf(ModelConfig.GROUNDING_MODEL, ModelConfig.GROUNDING_MODEL_FALLBACK).distinct()
        var last: BrainResult.Failure = BrainResult.Failure(id, BrainError.Unavailable)
        for ((index, model) in models.withIndex()) {
            val outcome = oneGrounded(request, model)
            if (outcome is BrainResult.Answer) return outcome
            last = outcome as BrainResult.Failure
            val rejected = lastModelRejected
            lastModelRejected = false
            if (!rejected || index == models.lastIndex) return last
            Log.w(TAG, "grounding model rejected model=$model fallback=${models[index + 1]}")
        }
        return last
    }

    private suspend fun oneGrounded(request: BrainRequest, model: String): BrainResult {
        val count = usage.snapshot().groundedRequests
        JarvisLog.i(SEARCH, "grounded begin model=$model count=$count")
        Log.i(TAG, "text begin model=$model backend=${GeminiModels.BACKEND} grounding=search")
        return try {
            usage.recordTextRequest()
            val spoken = withTimeout(GROUND_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    try {
                        grounded(request, model)
                    } catch (cancelled: CancellationException) {
                        if (cancelled is TimeoutCancellationException) throw cancelled
                        if (!currentCoroutineContext().isActive) throw cancelled
                        throw TextCancelled(cancelled)
                    }
                }
            }
            val text = spoken.text.trim()
            if (text.isEmpty()) {
                Log.w(TAG, "text blank model=$model grounding=search")
                JarvisLog.i(SEARCH, "grounded blank model=$model")
                BrainResult.Failure(id, BrainError.Unavailable)
            } else {
                Log.i(TAG, "text result model=$model chars=${text.length} preview=${preview(text)}")
                JarvisLog.i(SEARCH, "path=grounding model=$model")
                BrainResult.Answer(
                    brainId = id,
                    accent = accent,
                    text = text,
                    search = spoken.search,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "text timeout model=$model ms=$GROUND_TIMEOUT_MS", timeout)
            JarvisLog.i(SEARCH, "grounded timeout model=$model")
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (wrapped: TextCancelled) {
            val cause = wrapped.cause ?: wrapped
            Log.w(TAG, "text cancelled model=$model ${cause.causeChain()}", cause)
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            Log.w(TAG, "text cancelled model=$model ${cancelled.causeChain()}", cancelled)
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (error: Throwable) {
            lastModelRejected = error.isModelRejected()
            val line = QuotaSignals.failureLine(model, error)
            val body = QuotaSignals.failureBody(error)
            Log.w(TAG, line)
            Log.w(TAG, "grounded failure body=$body")
            JarvisLog.i(SEARCH, line)
            JarvisLog.i(SEARCH, "grounded failure body=$body")
            if (lastModelRejected) {
                Log.w(TAG, "text model not found model=$model ${error.causeChain()}", error)
            }
            error.toBrainFailure(id, cooldown, time)
        }
    }

    private suspend fun grounded(request: BrainRequest, model: String): Spoken {
        val fitter = PromptFitter()
        val assembled = fitter.memorySystem(
            baseSystem = ModelConfig.SYSTEM_PROMPT,
            persona = request.persona,
            facts = request.facts,
            summary = request.summary,
            charBudget = PromptFitter.GEMINI_SYSTEM_CHARS,
        )
        val fitted = fitter.fit(assembled, request.turns, request.utterance)
        val system = fitted.system.ifBlank { ModelConfig.SYSTEM_PROMPT } + "\n" + GROUNDING_NOTE +
            "\n" + AnswerLanguage.instruction(request.utterance)
        val generative = GeminiModels.grounded(system, model)
        val history = mutableListOf<Content>()
        for (turn in fitted.turns) {
            val role = if (turn.speaker == Speaker.USER) "user" else "model"
            history += content(role) { text(turn.text) }
        }
        if (history.isEmpty()) history += content("user") { text(request.utterance) }
        val response = generative.generateContent(history)
        val bits = response.toGroundedSpeech()
        val domainList = bits.domains.joinToString(",").ifBlank { "none" }
        JarvisLog.i(
            SEARCH,
            "grounded done model=$model domains=$domainList queries=${bits.queries} " +
                "suggestions=${if (bits.suggestions) "yes" else "no"} sources=${bits.search?.sources?.size ?: 0}",
        )
        return Spoken(bits.text, null, bits.search)
    }

    private suspend fun complete(request: BrainRequest, model: String): Spoken {
        val fitter = PromptFitter()
        val assembled = fitter.memorySystem(
            baseSystem = ModelConfig.SYSTEM_PROMPT,
            persona = request.persona,
            facts = request.facts,
            summary = request.summary,
            charBudget = PromptFitter.GEMINI_SYSTEM_CHARS,
        )
        val fitted = fitter.fit(assembled, request.turns, request.utterance)
        val system = fitted.system.ifBlank { ModelConfig.SYSTEM_PROMPT } +
            "\n" + AnswerLanguage.instruction(request.utterance)
        val generative = GeminiModels.text(
            registry,
            system,
            model,
        )
        val history = mutableListOf<Content>()
        for (turn in fitted.turns) {
            val role = if (turn.speaker == Speaker.USER) "user" else "model"
            history += content(role) { text(turn.text) }
        }
        if (history.isEmpty()) history += content("user") { text(request.utterance) }
        Log.i(TAG, "text request model=$model turns=${history.size}")
        val utterance = request.utterance
        var response = generative.generateContent(history)
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
            response = generative.generateContent(history)
        }
        val text = response.text?.takeIf { it.isNotBlank() } ?: toolText.toString()
        return Spoken(text, permission)
    }

    private data class Spoken(
        val text: String,
        val needsPermission: String?,
        val search: SearchDisplay? = null,
    )

    /** A child socket cancelled itself. This is not a cancelled question. */
    private class TextCancelled(cause: CancellationException) : Exception(cause.message, cause)

    @Volatile private var lastModelRejected: Boolean = false

    companion object {
        const val ID = "gemini-text"
        private const val SEARCH = "JarvisSearch"
        private const val TEXT_TIMEOUT_MS = 8_000L
        private const val GROUND_TIMEOUT_MS = 25_000L
        private const val MAX_TOOL_ROUNDS = 4
        private const val SUMMARY_SYSTEM =
            "Summarize the conversation memory in a few short sentences. " +
                "Reply with the summary only. Do not use tools."
        private const val GROUNDING_NOTE =
            "Google Search is available for this turn. There are no phone tools on this turn. " +
            "Search before you answer. Reply in one or two short spoken sentences in the user's language. " +
            "Do not include URLs, markdown, asterisks, or citation numbers."
    }
}

private fun preview(text: String): String {
    return redact(text.replace('\n', ' ')).take(80)
}
