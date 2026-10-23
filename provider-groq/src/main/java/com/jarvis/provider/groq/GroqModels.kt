package com.jarvis.provider.groq

import com.jarvis.core.brain.JarvisPrompt
import java.time.Duration

/** Chat and Whisper ids. Change them here, nowhere else. */
object GroqModels {
    val CHAT = listOf(
        "openai/gpt-oss-120b",
        "qwen/qwen3.8-27b",
        "openai/gpt-oss-20b",
    )

    const val WHISPER = "whisper-large-v3-turbo"

    /** Stop a model here so the org stays under the 200K tokens/day cap. */
    const val DAILY_TOKEN_CAP = 180_000
    const val DAILY_TOKEN_LIMIT = 200_000

    val USAGE_WINDOW: Duration = Duration.ofHours(24)
    const val TPM_COOLDOWN_SECONDS = 60L
    const val PCM_SAMPLE_RATE = 16_000

    const val SYSTEM = JarvisPrompt.SYSTEM
}
