package com.jarvis.provider.groq

import java.util.concurrent.TimeUnit
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
            GroqProtocol.classifyChat(status, retryAfter, body)
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
                    parse(response.code, response.header("Retry-After"), body)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failure
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val WAV = "audio/wav".toMediaType()
    }
}
