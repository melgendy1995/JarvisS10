package com.jarvis.provider.groq

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface GroqTransport {
    suspend fun complete(request: GroqChatRequest): GroqChatResult
    suspend fun transcribe(wav: ByteArray): GroqTranscript
    suspend fun search(query: String): LiveSearchResult
}

fun groqTransport(proxyUrl: () -> String, bearer: () -> String): GroqTransport {
    return OkHttpGroqTransport(groqHttpClient(), proxyUrl, bearer)
}

internal fun groqHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/**
 * OpenAI-compatible chat and audio calls. [proxyUrl] is the Worker origin.
 * The Authorization value is the Worker bearer, never a Groq key.
 */
internal class OkHttpGroqTransport(
    private val client: OkHttpClient,
    private val proxyUrl: () -> String,
    private val bearer: () -> String,
) : GroqTransport {
    override suspend fun complete(request: GroqChatRequest): GroqChatResult {
        val endpoint = endpoint("/chat/completions") ?: return GroqChatResult.NotConfigured
        val http = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${bearer().trim()}")
            .header("Accept", "application/json")
            .post(GroqProtocol.chatBody(request).toRequestBody(JSON))
            .build()
        return execute(http, GroqChatResult.Unavailable) { status, retryAfter, body ->
            if (request.browserSearch) {
                GroqProtocol.classifyBrowser(status, body)
            } else {
                GroqProtocol.classifyChat(status, retryAfter, body)
            }
        }
    }

    override suspend fun search(query: String): LiveSearchResult {
        val endpoint = endpoint("/search") ?: return LiveSearchResult.Unavailable
        val payload = buildJsonObject { put("q", JsonPrimitive(query.take(240))) }.toString()
        val http = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${bearer().trim()}")
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()
        return execute(http, LiveSearchResult.Unavailable) { status, _, body ->
            when {
                status == 429 -> LiveSearchResult.Limited
                status !in 200..299 -> LiveSearchResult.Unavailable
                else -> {
                    val items = GroqProtocol.parseSearch(body)
                    if (items.isEmpty()) LiveSearchResult.Unavailable else LiveSearchResult.Ok(items)
                }
            }
        }
    }

    override suspend fun transcribe(wav: ByteArray): GroqTranscript {
        val endpoint = endpoint("/audio/transcriptions") ?: return GroqTranscript.Failed
        // Model field first so a proxy can read the id without scanning the WAV bytes.
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", GroqModels.WHISPER)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "utterance.wav", wav.toRequestBody(WAV))
            .build()
        val http = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${bearer().trim()}")
            .post(multipart)
            .build()
        return execute(http, GroqTranscript.Failed) { status, _, body ->
            if (status !in 200..299) {
                GroqTranscript.Failed
            } else {
                val text = GroqProtocol.parseTranscript(body)
                if (text == null) GroqTranscript.Failed else GroqTranscript.Ok(text)
            }
        }
    }

    private fun safeReason(body: String): String {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body)
        }.getOrNull() as? JsonObject ?: return ""
        val error = root["error"]
        val raw = when (error) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                ?: (error["code"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.trim().orEmpty()
        val reason = raw
            .replace(Regex("""gsk_[A-Za-z0-9_\-]+"""), "gsk_[redacted]")
            .replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
            .replace(Regex("""tvly-[A-Za-z0-9_\-]+"""), "[redacted]")
            .replace(Regex("""BSA[A-Za-z0-9_\-]{8,}"""), "[redacted]")
            .replace(Regex("""\s+"""), " ")
        if (reason.isEmpty() || reason.any { it.isISOControl() }) return ""
        return reason.take(180)
    }

    private fun endpoint(path: String): String? {
        val base = GroqProxy.normalize(proxyUrl()) ?: return null
        return base + path
    }

    private suspend fun <T> execute(
        request: Request,
        failure: T,
        parse: (status: Int, retryAfter: String?, body: String) -> T,
    ): T {
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val reason = safeReason(body)
                    val reasonLog = if (reason.isEmpty()) "" else " reason=$reason"
                    Log.i(TAG, "http path=${request.url.encodedPath} status=${response.code}$reasonLog")
                    parse(response.code, response.header("Retry-After"), body)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "http failed path=${request.url.encodedPath} error=${error.javaClass.simpleName}")
            failure
        }
    }

    private companion object {
        const val TAG = "JarvisGroq"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val WAV = "audio/wav".toMediaType()
    }
}
