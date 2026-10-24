package com.jarvis.provider.groq

import com.jarvis.core.JarvisLog
import com.jarvis.core.brain.Brain
import com.jarvis.core.brain.BrainAccent
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.FittedPrompt
import com.jarvis.core.brain.Freshness
import com.jarvis.core.brain.PromptFitter
import com.jarvis.core.brain.SearchDisplay
import com.jarvis.core.brain.SearchSource
import com.jarvis.core.brain.Speaker
import com.jarvis.core.brain.SpokenText
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
        if (Freshness.needsLiveInfo(request.utterance)) {
            return replyFresh(request)
        }
        return replyChat(request)
    }

    /**
     * Gemini grounding already failed or was capped. Browser search is next,
     * then Worker results phrased only from those hits.
     */
    private suspend fun replyFresh(request: BrainRequest): BrainResult {
        val gate = readiness()
        if (gate != null) return gate
        val browsed = browse(request)
        if (browsed != null) return browsed
        val found = try {
            transport.search(request.utterance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("search failed: ${error.javaClass.simpleName}")
            LiveSearchResult.Unavailable
        }
        if (found is LiveSearchResult.Ok) {
            val phrased = phrase(request, found.items)
            if (phrased != null) return phrased
            return sourced(request, found.items)
        }
        val reason = if (found is LiveSearchResult.Limited) "daily-cap" else "unavailable"
        JarvisLog.i("JarvisSearch", "search skipped reason=$reason")
        log("search skipped reason=$reason")
        return liveSearchUnavailable(request, "all-paths-failed")
    }

    private suspend fun browse(request: BrainRequest): BrainResult.Answer? {
        var stop = false
        for (model in GroqModels.BROWSER_SEARCH) {
            if (stop) break
            if (!rotation.isAvailable(model)) {
                log("browser skip cooling model=$model")
                continue
            }
            val result = try {
                transport.complete(browserRequest(model, request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log("browser failed: ${error.javaClass.simpleName}")
                GroqChatResult.Unavailable
            }
            when (result) {
                is GroqChatResult.Ok -> {
                    val text = brief(result.message.content.orEmpty())
                    if (text.isBlank() || isSearchRefusal(text)) {
                        log("browser blank model=$model")
                        continue
                    }
                    rotation.recordTokens(model, result.totalTokens ?: 1)
                    JarvisLog.i("JarvisSearch", "path=browser model=$model")
                    log("path=browser model=$model")
                    return BrainResult.Answer(
                        brainId = id,
                        accent = accent,
                        text = text,
                        search = sourcesFromText(result.message.content.orEmpty()),
                    )
                }
                is GroqChatResult.SearchDenied -> {
                    JarvisLog.i(
                        "JarvisSearch",
                        "browser_search skipped model=$model reason=${result.reason} localCap=${result.localCap}",
                    )
                    log("browser_search skipped model=$model reason=${result.reason}")
                    if (result.reason == "quota" && !result.localCap) {
                        rotation.coolUntil(model, clock.now().plusSeconds(GroqModels.TPM_COOLDOWN_SECONDS))
                    } else {
                        stop = true
                    }
                }
                is GroqChatResult.Limited -> {
                    JarvisLog.i("JarvisSearch", "browser_search skipped model=$model reason=quota")
                    log("browser_search skipped model=$model reason=quota")
                    rotation.coolUntil(model, cooldownUntil(Turn.Limited(result.retryAfter, result.daily)))
                }
                GroqChatResult.NotConfigured -> {
                    JarvisLog.i("JarvisSearch", "browser_search skipped model=$model reason=permission")
                    log("browser_search skipped model=$model reason=permission")
                    stop = true
                }
                GroqChatResult.Unavailable -> {
                    JarvisLog.i("JarvisSearch", "browser_search skipped model=$model reason=unavailable")
                    log("browser_search skipped model=$model reason=unavailable")
                }
            }
        }
        return null
    }

    private suspend fun phrase(request: BrainRequest, items: List<LiveHit>): BrainResult.Answer? {
        val prompt = resultsPrompt(request.utterance, items)
        for (model in GroqModels.BROWSER_SEARCH) {
            if (!rotation.isAvailable(model)) continue
            val result = try {
                transport.complete(
                    GroqChatRequest(
                        model = model,
                        messages = listOf(
                            OutgoingMessage(role = "system", content = PHRASE_SYSTEM),
                            OutgoingMessage(role = "user", content = prompt),
                        ),
                        tools = emptyList(),
                        maxCompletionTokens = 256,
                        reasoningEffort = "low",
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log("phrase failed: ${error.javaClass.simpleName}")
                return null
            }
            when (result) {
                is GroqChatResult.Ok -> {
                    val text = SpokenText.forSpeech(result.message.content.orEmpty())
                    if (text.isBlank() || isSearchRefusal(text)) {
                        log("phrase refused model=$model")
                        continue
                    }
                    rotation.recordTokens(model, result.totalTokens ?: 1)
                    JarvisLog.i("JarvisSearch", "path=search model=$model items=${items.size}")
                    log("path=search model=$model items=${items.size}")
                    return BrainResult.Answer(
                        brainId = id,
                        accent = accent,
                        text = text,
                        search = display(items),
                    )
                }
                is GroqChatResult.Limited -> {
                    log("phrase limited model=$model")
                    rotation.coolUntil(model, cooldownUntil(Turn.Limited(result.retryAfter, result.daily)))
                }
                else -> log("phrase unavailable model=$model")
            }
        }
        return null
    }

    private fun sourced(request: BrainRequest, items: List<LiveHit>): BrainResult.Answer {
        val hit = items.first()
        val arabic = request.utterance.any { it in '\u0600'..'\u06FF' }
        val source = hit.source.ifBlank { if (arabic) "الخبر" else "the news" }
        val whenText = hit.date.ifBlank { if (arabic) "اليوم" else "today" }
        val text = if (arabic) {
            "من $source بتاريخ $whenText: ${hit.title}."
        } else {
            "From $source on $whenText: ${hit.title}."
        }
        JarvisLog.i("JarvisSearch", "path=search-direct items=${items.size}")
        log("path=search-direct items=${items.size}")
        return BrainResult.Answer(
            brainId = id,
            accent = accent,
            text = text,
            search = display(items),
        )
    }

    private fun readiness(): BrainResult? {
        val proxyReady = GroqProxy.normalize(proxyUrl()) != null
        val bearerReady = bearerPresent()
        val onlineNow = try {
            online()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("online check failed: ${error.javaClass.simpleName}")
            false
        }
        log("fresh proxy=$proxyReady bearer=$bearerReady online=$onlineNow")
        if (!proxyReady || !bearerReady) {
            log("skip not configured")
            return BrainResult.Failure(id, BrainError.NotConfigured)
        }
        if (!onlineNow) {
            log("skip offline")
            return BrainResult.Failure(id, BrainError.Unavailable)
        }
        return null
    }

    private fun browserRequest(model: String, request: BrainRequest): GroqChatRequest {
        return GroqChatRequest(
            model = model,
            messages = listOf(
                OutgoingMessage(role = "system", content = BROWSER_SYSTEM),
                OutgoingMessage(role = "user", content = request.utterance),
            ),
            tools = emptyList(),
            browserSearch = true,
        )
    }

    private suspend fun replyChat(request: BrainRequest): BrainResult {
        val proxyReady = GroqProxy.normalize(proxyUrl()) != null
        val bearerReady = bearerPresent()
        val onlineNow = try {
            online()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("online check failed: ${error.javaClass.simpleName}")
            false
        }
        log("reply proxy=$proxyReady bearer=$bearerReady online=$onlineNow")
        if (!proxyReady || !bearerReady) {
            log("skip not configured")
            return BrainResult.Failure(id, BrainError.NotConfigured)
        }
        if (!onlineNow) {
            log("skip offline")
            return BrainResult.Failure(id, BrainError.Unavailable)
        }
        return try {
            replyOnline(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("reply failed: ${error.javaClass.simpleName}")
            BrainResult.Failure(id, BrainError.Unavailable)
        }
    }

    private fun liveSearchUnavailable(request: BrainRequest, reason: String): BrainResult {
        val arabic = request.utterance.any { it in '\u0600'..'\u06FF' }
        val text = if (arabic) UNAVAILABLE_AR else UNAVAILABLE_EN
        JarvisLog.i("JarvisSearch", "live search unavailable reason=$reason")
        log("live search unavailable reason=$reason")
        return BrainResult.Answer(brainId = id, accent = accent, text = text)
    }

    private fun resultsPrompt(utterance: String, items: List<LiveHit>): String {
        val lines = items.mapIndexed { index, hit ->
            val extract = hit.extract.trim()
            val head = "${index + 1}. ${hit.title} — ${hit.source} — ${hit.date}"
            if (extract.isEmpty()) head else "$head\n$extract"
        }
        return "Question: ${utterance.trim()}\n\nResults:\n${lines.joinToString("\n")}"
    }

    private fun display(items: List<LiveHit>): SearchDisplay? {
        val sources = items.mapNotNull { hit ->
            val link = hit.link.trim()
            if (!link.startsWith("http")) return@mapNotNull null
            val title = hit.source.ifBlank { hit.title }.ifBlank { "source" }
            SearchSource(title = title, uri = link)
        }.take(4)
        if (sources.isEmpty()) return null
        return SearchDisplay(sources = sources)
    }

    private fun sourcesFromText(raw: String): SearchDisplay? {
        val urls = URLS.findAll(raw).map { it.value.trimEnd('.', ',', ';', ')') }.distinct().take(4).toList()
        if (urls.isEmpty()) return null
        return SearchDisplay(
            sources = urls.map { url ->
                val host = runCatching { java.net.URI(url).host }
                    .getOrNull()
                    ?.removePrefix("www.")
                    ?.ifBlank { null }
                    ?: "source"
                SearchSource(title = host, uri = url)
            },
        )
    }

    private fun brief(raw: String): String {
        val clean = SpokenText.forSpeech(raw)
        if (clean.length <= 420) return clean
        val parts = clean.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
        val tail = parts.takeLast(2).joinToString(" ")
        return tail.ifBlank { clean.take(420) }
    }

    private fun isSearchRefusal(text: String): Boolean {
        val folded = text.lowercase()
        return folded.contains("can't search") ||
            folded.contains("cannot search") ||
            folded.contains("can not search") ||
            folded.contains("live search is unavailable") ||
            folded.contains("don't have access") ||
            folded.contains("do not have access") ||
            folded.contains("غير متاح") ||
            folded.contains("لا أستطيع البحث") ||
            folded.contains("ما اقدر ابحث")
    }

    private fun bearerPresent(): Boolean {
        val token = try {
            bearer().trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("bearer read failed: ${error.javaClass.simpleName}")
            return false
        }
        if (token.isEmpty()) return false
        log(authNote(token))
        return true
    }

    private fun authNote(token: String): String {
        val kind = when {
            UUID.matches(token) -> "uuid"
            token.startsWith("gsk_") -> "gsk"
            else -> "other"
        }
        return "auth len=${token.length} kind=$kind"
    }

    private suspend fun replyOnline(request: BrainRequest): BrainResult {
        val models = rotation.models()
        if (models.isEmpty()) {
            log("skip no models")
            return BrainResult.Failure(id, BrainError.NotConfigured)
        }
        val start = rotation.peekIndex()
        var limited = false
        for (offset in models.indices) {
            val index = (start + offset) % models.size
            val model = models[index]
            if (!rotation.isAvailable(model)) {
                log("model cooling model=$model")
                continue
            }
            val fitted = fitter.fit(systemFor(request), request.turns, request.utterance)
            log("prompt ~${fitted.estimatedTokens} tokens model=$model")
            when (val outcome = complete(model, request.utterance, fitted)) {
                is Turn.Spoken -> {
                    rotation.recordTokens(model, outcome.tokens)
                    rotation.commitIndex((index + 1) % models.size)
                    log("answer model=$model")
                    return BrainResult.Answer(
                        brainId = id,
                        accent = accent,
                        text = outcome.text,
                        needsPermission = outcome.needsPermission,
                    )
                }
                is Turn.Limited -> {
                    limited = true
                    log("limited model=$model daily=${outcome.daily}")
                    rotation.coolUntil(model, cooldownUntil(outcome))
                }
                Turn.NotConfigured -> {
                    log("not configured model=$model")
                    return BrainResult.Failure(id, BrainError.NotConfigured)
                }
                Turn.Unavailable -> {
                    log("unavailable model=$model")
                    return BrainResult.Failure(id, BrainError.Unavailable)
                }
            }
        }
        if (limited || !rotation.hasAvailableModel()) {
            val wait = rotation.soonestWait()
            log("cooling waitSec=${wait.seconds}")
            return BrainResult.Failure(
                id,
                BrainError.RateLimited(wait, isDaily = wait >= Duration.ofHours(1)),
            )
        }
        log("unavailable after models")
        return BrainResult.Failure(id, BrainError.Unavailable)
    }

    override suspend fun summarize(prompt: String): BrainResult {
        if (prompt.isBlank()) return BrainResult.Failure(id, BrainError.Unavailable)
        if (!configured()) return BrainResult.Failure(id, BrainError.NotConfigured)
        if (!online()) return BrainResult.Failure(id, BrainError.Unavailable)
        val models = rotation.models()
        if (models.isEmpty()) return BrainResult.Failure(id, BrainError.NotConfigured)
        val fitted = fitter.fit(SUMMARY_SYSTEM, emptyList(), prompt)
        val start = rotation.peekIndex()
        var limited = false
        for (offset in models.indices) {
            val index = (start + offset) % models.size
            val model = models[index]
            if (!rotation.isAvailable(model)) continue
            val messages = ArrayList<OutgoingMessage>()
            if (fitted.system.isNotEmpty()) {
                messages += OutgoingMessage(role = "system", content = fitted.system)
            }
            val user = fitted.turns.lastOrNull()?.text?.takeIf { it.isNotBlank() } ?: prompt.trim()
            messages += OutgoingMessage(role = "user", content = user)
            val result = try {
                transport.complete(GroqChatRequest(model, messages.toList(), emptyList()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return BrainResult.Failure(id, BrainError.Unavailable)
            }
            when (result) {
                is GroqChatResult.Ok -> {
                    val text = result.message.content?.trim().orEmpty()
                    if (text.isEmpty()) return BrainResult.Failure(id, BrainError.Unavailable)
                    val spent = result.totalTokens ?: fitted.estimatedTokens.coerceAtLeast(1)
                    rotation.recordTokens(model, spent)
                    return BrainResult.Answer(brainId = id, accent = accent, text = text)
                }
                is GroqChatResult.Limited -> {
                    limited = true
                    rotation.coolUntil(model, cooldownUntil(Turn.Limited(result.retryAfter, result.daily)))
                }
                GroqChatResult.NotConfigured -> return BrainResult.Failure(id, BrainError.NotConfigured)
                GroqChatResult.Unavailable -> return BrainResult.Failure(id, BrainError.Unavailable)
                is GroqChatResult.SearchDenied -> return BrainResult.Failure(id, BrainError.Unavailable)
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
        return fitter.memorySystem(
            baseSystem = systemPrompt,
            persona = request.persona,
            facts = request.facts,
            summary = request.summary,
            charBudget = PromptFitter.GROQ_SYSTEM_CHARS,
        )
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
                GroqChatResult.Unavailable, is GroqChatResult.SearchDenied -> return Turn.Unavailable
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
        const val UNAVAILABLE_EN = "Live search is unavailable right now."
        const val UNAVAILABLE_AR = "البحث المباشر غير متاح الآن."
        private val UUID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private const val MAX_TOOL_ROUNDS = 4
        private const val SUMMARY_SYSTEM =
            "Summarize the conversation memory in a few short sentences. " +
                "Reply with the summary only. Do not call tools."
        private const val BROWSER_SYSTEM =
            "You are Jarvis, a spoken assistant on a phone in Saudi Arabia. " +
                "Search the web for this question. Answer in one or two short sentences " +
                "in the user's language. Name the source and the date. " +
                "No URLs, no markdown, and no citation markers."
        private const val PHRASE_SYSTEM =
            "You are Jarvis, a spoken assistant. Answer in one or two short sentences " +
                "in the user's language. Use only the search results in the user message. " +
                "Do not use prior knowledge. Name the source and the date. " +
                "Do not include URLs or markdown. If the results do not contain the answer, say so."
        private val URLS = Regex("""https?://[^\s<>\]]+""")
    }
}
