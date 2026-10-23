package com.jarvis.provider.gemini

import com.jarvis.core.brain.JarvisPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class JarvisPromptWiringTest {
    @Test
    fun geminiUsesTheSharedCloudPrompt() {
        assertEquals(JarvisPrompt.SYSTEM, ModelConfig.SYSTEM_PROMPT)
    }

    @Test
    fun liveVoiceIsCharon() {
        assertEquals("Charon", ModelConfig.LIVE_VOICE)
    }
}
