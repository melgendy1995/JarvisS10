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

    /**
     * groq/compound was decommissioned on 2026-09-21.
     * Fresh questions use built-in browser_search on these gpt-oss ids, then Worker /search.
     * The Worker caps browser_search at [BROWSER_DAILY_CAP] and completions at [BROWSER_MAX_TOKENS].
     */
    val BROWSER_SEARCH = listOf(
        "openai/gpt-oss-120b",
        "openai/gpt-oss-20b",
    )
    const val BROWSER_DAILY_CAP = 100
    const val BROWSER_MAX_TOKENS = 1024

    /** Stop a model here so the org stays under the 200K tokens/day cap. */
    const val DAILY_TOKEN_CAP = 180_000
    const val DAILY_TOKEN_LIMIT = 200_000

    val USAGE_WINDOW: Duration = Duration.ofHours(24)
    const val TPM_COOLDOWN_SECONDS = 60L
    const val PCM_SAMPLE_RATE = 16_000

    const val SYSTEM = JarvisPrompt.SYSTEM
}
