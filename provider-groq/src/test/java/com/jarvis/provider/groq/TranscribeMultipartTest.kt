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
    fun `worker tolerant regex matches the app body`() {
        val head = multipartText().take(4096)
        val tolerant = Regex("""name="model"\r?\n(?:[^\r\n]+\r?\n)*?\r?\n([^\r\n]+)""")
        assertTrue(tolerant.containsMatchIn(head))
        assertEquals("whisper-large-v3-turbo", tolerant.find(head)?.groupValues?.get(1)?.trim())
    }

    private fun multipartText(): String {
        val body = OkHttpGroqTransport.transcriptionMultipart(ByteArray(8))
        val buffer = Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        return String(bytes, StandardCharsets.UTF_8)
    }
}
