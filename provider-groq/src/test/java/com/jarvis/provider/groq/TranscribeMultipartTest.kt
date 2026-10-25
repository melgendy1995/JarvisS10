package com.jarvis.provider.groq

import java.nio.charset.StandardCharsets
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeMultipartTest {
    @Test
    fun `model part has no content length between disposition and value`() {
        val text = multipartText()
        assertTrue(text.contains("name=\"model\"\r\n\r\nwhisper-large-v3-turbo"))
        assertFalse(text.contains("name=\"model\"\r\nContent-Length"))
    }

    @Test
    fun `language part exists for ar and has no content length`() {
        val text = multipartText(language = "ar")
        assertTrue(text.contains("name=\"language\"\r\n\r\nar"))
        assertFalse(text.contains("name=\"language\"\r\nContent-Length"))
    }

    @Test
    fun `language part is absent for auto mode`() {
        val text = multipartText(language = null)
        assertFalse(text.contains("name=\"language\""))
    }

    @Test
    fun `file part is a wav`() {
        val text = multipartText()
        assertTrue(text.contains("name=\"file\""))
        assertTrue(text.contains("utterance.wav"))
    }

    @Test
    fun `worker tolerant regex matches the app body`() {
        val head = multipartText().take(4096)
        val tolerant = Regex("""name="model"\r?\n(?:[^\r\n]+\r?\n)*?\r?\n([^\r\n]+)""")
        assertTrue(tolerant.containsMatchIn(head))
        assertEquals("whisper-large-v3-turbo", tolerant.find(head)?.groupValues?.get(1)?.trim())
    }

    private fun multipartText(language: String? = null): String {
        val body = OkHttpGroqTransport.transcriptionMultipart(ByteArray(8), language)
        val buffer = Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        return String(bytes, StandardCharsets.UTF_8)
    }
}
