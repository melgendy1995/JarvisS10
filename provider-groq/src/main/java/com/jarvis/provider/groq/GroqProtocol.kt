package com.jarvis.provider.groq

import java.net.URI
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

data class ToolCallSpec(
    val id: String,
    val name: String,
    val arguments: String,
)

data class OutgoingMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallSpec> = emptyList(),
)

data class GroqChatRequest(
    val model: String,
    val messages: List<OutgoingMessage>,
    val tools: List<JsonObject>,
    /** Server-side Groq browser search. Function tools are not sent with this. */
    val browserSearch: Boolean = false,
    val maxCompletionTokens: Int? = null,
    val reasoningEffort: String? = null,
)

data class LiveHit(
    val title: String,
    val source: String,
    val date: String,
    val link: String,
    val kind: String = "",
    val extract: String = "",
)

sealed class LiveSearchResult {
    data class Ok(val items: List<LiveHit>) : LiveSearchResult()
    data object Limited : LiveSearchResult()
    data object Unavailable : LiveSearchResult()
}

data class AssistantMessage(
    val content: String?,
    val toolCalls: List<ToolCallSpec>,
)

sealed class GroqChatResult {
    data class Ok(val message: AssistantMessage, val totalTokens: Int?) : GroqChatResult()
    data class Limited(val retryAfter: Duration?, val daily: Boolean) : GroqChatResult()
    data object NotConfigured : GroqChatResult()
    data object Unavailable : GroqChatResult()

    /**
     * Browser search cannot run. [reason] is quota, plan, or permission.
     * [localCap] is the Worker daily cap, not a Groq model limit.
     */
    data class SearchDenied(
        val reason: String,
        val localCap: Boolean = false,
    ) : GroqChatResult()
}

sealed class GroqTranscript {
    /**
     * A Whisper transcript. [language] is the detected language when
     * response_format carried it, [noSpeechProb] and [avgLogprob] are the segment
     * means (-1 when the body had no segments), [confidence] a 0..1 value or -1
     * when segments were missing.
     */
    data class Ok(
        val text: String,
        val language: String? = null,
        val noSpeechProb: Float = -1f,
        val avgLogprob: Float = -1f,
        val confidence: Float = -1f,
    ) : GroqTranscript()
    data object Failed : GroqTranscript()
}

/** The phone talks only to the Worker. api.groq.com is rejected so a key is never sent there. */
object GroqProxy {
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "api.groq.com" || host.endsWith(".groq.com")) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed
    }
}

object GroqProtocol {
    fun chatBody(request: GroqChatRequest): String {
        return buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("messages", JsonArray(request.messages.map { it.toJson() }))
            if (request.browserSearch) {
                put("max_completion_tokens", JsonPrimitive(GroqModels.BROWSER_MAX_TOKENS))
                put("reasoning_effort", JsonPrimitive("low"))
                put("tool_choice", JsonPrimitive("required"))
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("type", JsonPrimitive("browser_search")) })
                })
            } else {
                if (request.maxCompletionTokens != null) {
                    put("max_completion_tokens", JsonPrimitive(request.maxCompletionTokens))
                }
                if (!request.reasoningEffort.isNullOrBlank()) {
                    put("reasoning_effort", JsonPrimitive(request.reasoningEffort))
                }
                if (request.tools.isNotEmpty()) {
                    put("tools", JsonArray(request.tools))
                    put("tool_choice", JsonPrimitive("auto"))
                }
            }
        }.toString()
    }

    /** Quota, plan, and permission failures stay distinct from a bad Worker bearer. */
    fun classifyBrowser(status: Int, body: String): GroqChatResult {
        if (status in 200..299) {
            val message = parseAssistant(body) ?: return GroqChatResult.Unavailable
            if (message.content.isNullOrBlank()) return GroqChatResult.Unavailable
            return GroqChatResult.Ok(message, parseTotalTokens(body))
        }
        if (status == 401) return GroqChatResult.NotConfigured
        val text = body.lowercase()
        val localCap = text.contains("browser daily limit") || text.contains("proxy daily limit")
        val quota = status == 429 ||
            text.contains("quota") ||
            text.contains("rate_limit") ||
            text.contains("rate limit") ||
            text.contains("daily limit") ||
            text.contains("tokens per")
        val plan = status == 402 ||
            text.contains("plan") ||
            text.contains("billing") ||
            text.contains("upgrade") ||
            text.contains("payment required")
        val permission = status == 403 ||
            text.contains("permission") ||
            text.contains("not authorized") ||
            text.contains("not allowed") ||
            text.contains("access denied") ||
            text.contains("does not support browser")
        if (quota && !plan) return GroqChatResult.SearchDenied("quota", localCap)
        if (plan) return GroqChatResult.SearchDenied("plan", localCap)
        if (permission) return GroqChatResult.SearchDenied("permission", localCap)
        return GroqChatResult.Unavailable
    }

    fun parseSearch(body: String): List<LiveHit> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = text(obj, "title") ?: return@mapNotNull null
            LiveHit(
                title = title,
                source = text(obj, "source").orEmpty(),
                date = text(obj, "date").orEmpty(),
                link = text(obj, "link").orEmpty(),
                kind = text(obj, "kind").orEmpty(),
                extract = text(obj, "extract").orEmpty(),
            )
        }
    }

    fun classifyChat(status: Int, retryAfterHeader: String?, body: String): GroqChatResult {
        if (status == 401 || status == 403) return GroqChatResult.NotConfigured
        if (isRateLimited(status, body)) {
            return GroqChatResult.Limited(
                retryAfter = parseRetryAfter(retryAfterHeader, body),
                daily = isDaily(body),
            )
        }
        if (status !in 200..299) return GroqChatResult.Unavailable
        val message = parseAssistant(body) ?: return GroqChatResult.Unavailable
        return GroqChatResult.Ok(message, parseTotalTokens(body))
    }

    fun parseTranscript(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return (root["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
    }

    /**
     * Verbose transcription JSON: text, language, and per-segment no_speech_prob
     * and avg_logprob. Tolerant: missing segments fall back to the plain text.
     */
    fun parseTranscriptDetailed(body: String): GroqTranscript {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return GroqTranscript.Failed
        val text = (root["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
            ?: return GroqTranscript.Failed
        val language = (root["language"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
        val segments = root["segments"] as? JsonArray ?: return GroqTranscript.Ok(text, language)
        val noSpeech = segments.meanOf("no_speech_prob")
        val logProb = segments.meanOf("avg_logprob")
        val confidence = if (noSpeech == null) {
            -1f
        } else {
            (1f - noSpeech).coerceIn(0f, 1f)
        }
        return GroqTranscript.Ok(
            text = text,
            language = language,
            noSpeechProb = noSpeech ?: -1f,
            avgLogprob = logProb ?: -1f,
            confidence = confidence,
        )
    }

    private fun JsonArray.meanOf(field: String): Float? {
        val values = mapNotNull { element ->
            (element as? JsonObject)?.get(field) as? JsonPrimitive
        }.mapNotNull { it.doubleOrNull }
        if (values.isEmpty()) return null
        return (values.average().toFloat())
    }

    fun parseRetryAfter(header: String?, body: String): Duration? {
        header?.trim()?.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return Duration.ofSeconds(seconds)
        }
        val match = RETRY_AFTER.find(body) ?: return null
        return Duration.ofSeconds(match.groupValues[1].toLong())
    }

    fun isDaily(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("tpd") ||
            text.contains("tokens per day") ||
            text.contains("per day") ||
            text.contains("daily")
    }

    private fun isRateLimited(status: Int, body: String): Boolean {
        if (status == 429) return true
        if (status !in 400..499) return false
        val text = body.lowercase()
        return text.contains("rate_limit") ||
            text.contains("rate limit") ||
            text.contains("tokens per minute") ||
            text.contains("tokens per day") ||
            text.contains("tpm") ||
            text.contains("tpd")
    }

    private fun parseAssistant(body: String): AssistantMessage? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val message = choice["message"] as? JsonObject ?: return null
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val function = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "call_$index"
            val arguments = when (val raw = function["arguments"]) {
                is JsonPrimitive -> raw.content
                is JsonObject -> raw.toString()
                else -> "{}"
            }
            ToolCallSpec(id, name, arguments)
        }
        return AssistantMessage(content, calls)
    }

    private fun text(obj: JsonObject, key: String): String? {
        return (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun parseTotalTokens(body: String): Int? {
        val usage = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)
            ?.get("usage") as? JsonObject ?: return null
        (usage["total_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { return it }
        val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val completion = (usage["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val sum = prompt + completion
        return sum.takeIf { it > 0 }
    }

    private fun OutgoingMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        if (content == null) put("content", JsonNull) else put("content", JsonPrimitive(content))
        if (!toolCallId.isNullOrBlank()) put("tool_call_id", JsonPrimitive(toolCallId))
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(call.id))
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(call.name))
                            put("arguments", JsonPrimitive(call.arguments))
                        })
                    })
                }
            })
        }
    }

    private val RETRY_AFTER = Regex(
        """retry-after["']?\s*[:=]\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    private val json = kotlinx.serialization.json.Json
}
