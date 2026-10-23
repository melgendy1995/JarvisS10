package com.jarvis.provider.groq

import java.time.Duration
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqProtocolTest {
    @Test
    fun classifiesRetryAfterAndDailyWording() {
        val limited = GroqProtocol.classifyChat(
            status = 429,
            retryAfterHeader = "45",
            body = """{"error":{"message":"Rate limit reached on tokens per minute","code":"rate_limit_exceeded"}}""",
        ) as GroqChatResult.Limited
        assertEquals(Duration.ofSeconds(45), limited.retryAfter)
        assertFalse(limited.daily)

        val daily = GroqProtocol.classifyChat(
            status = 429,
            retryAfterHeader = null,
            body = """{"error":{"message":"tokens per day limit reached","type":"tpd"}}""",
        ) as GroqChatResult.Limited
        assertTrue(daily.daily)
        assertEquals(null, daily.retryAfter)
    }

    @Test
    fun parsesToolCallsAndTokenUsage() {
        val body = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_9",
                    "type": "function",
                    "function": {"name": "flashlight", "arguments": "{\"enabled\":true}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 100, "completion_tokens": 12, "total_tokens": 112}
            }
        """.trimIndent()
        val ok = GroqProtocol.classifyChat(200, null, body) as GroqChatResult.Ok
        assertEquals("call_9", ok.message.toolCalls.single().id)
        assertEquals("flashlight", ok.message.toolCalls.single().name)
        assertEquals(112, ok.totalTokens)
    }

    @Test
    fun wavHeaderWrapsSixteenKilohertzPcm() {
        val pcm = ByteArray(320) { it.toByte() }
        val wav = WavPcm.encode(pcm, 16_000)
        assertEquals(364, wav.size)
        assertEquals("RIFF", wav.decodeAscii(0, 4))
        assertEquals("WAVE", wav.decodeAscii(8, 4))
        assertEquals(16_000, wav.leInt(24))
        assertEquals(320, wav.leInt(40))
        assertEquals(pcm[0], wav[44])
    }

    @Test
    fun whisperStaysBehindTheRecognizerAndOfflineBrains() {
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "what time is it",
                recognizerFailed = false,
                pcmBytes = 20_000,
                cloudNext = true,
            ),
        )
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = 20_000,
                cloudNext = false,
            ),
        )
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = false,
                pcmBytes = 20_000,
                cloudNext = true,
            ),
        )
        assertTrue(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = BackupSttPolicy.MIN_PCM_BYTES,
                cloudNext = true,
            ),
        )
    }

    @Test
    fun chatPostGoesToTheProxyWithTheWorkerBearer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Hi"}}],"usage":{"total_tokens":12}}"""),
        )
        server.start()
        try {
            val transport = OkHttpGroqTransport(
                client = OkHttpClient(),
                proxyUrl = { server.url("/").toString().trimEnd('/') },
                bearer = { "test-bearer" },
            )
            val result = transport.complete(
                GroqChatRequest(
                    model = GroqModels.CHAT[2],
                    messages = listOf(OutgoingMessage(role = "user", content = "Hi")),
                    tools = emptyList(),
                ),
            ) as GroqChatResult.Ok
            val recorded = server.takeRequest()
            assertEquals("Bearer test-bearer", recorded.getHeader("Authorization"))
            assertTrue(recorded.path!!.endsWith("/chat/completions"))
            assertFalse(recorded.requestUrl!!.host.contains("groq"))
            assertTrue(recorded.body.readUtf8().contains(GroqModels.CHAT[2]))
            assertEquals("Hi", result.message.content)
            assertEquals(12, result.totalTokens)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun directGroqHostIsNeverCalled() {
        val transport = OkHttpGroqTransport(
            client = OkHttpClient(),
            proxyUrl = { "https://api.groq.com/openai/v1" },
            bearer = { "gsk_not_a_real_key" },
        )
        runBlocking {
            val result = transport.complete(
                GroqChatRequest("openai/gpt-oss-20b", emptyList(), emptyList()),
            )
            assertEquals(GroqChatResult.NotConfigured, result)
        }
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.leInt(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }
}
