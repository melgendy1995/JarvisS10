package com.jarvis.provider.gemini

import com.jarvis.core.brain.JarvisPrompt
import java.time.ZoneId

/** Model ids and client caps. Change them here, nowhere else. The shared cloud prompt is [JarvisPrompt]. */
object ModelConfig {
    const val LIVE_MODEL = "gemini-3.1-flash-live-preview"
    /** Used only when the server rejects [LIVE_MODEL]. Firebase AI Logic on the Gemini Developer API supports this Live id. */
    const val LIVE_MODEL_FALLBACK = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val TEXT_MODEL = "gemini-3.1-flash-lite"
    /** Used only when the server says [TEXT_MODEL] is missing. */
    const val TEXT_MODEL_FALLBACK = "gemini-3.5-flash-lite"

    const val INPUT_SAMPLE_RATE_HZ = 16_000
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000

    /** Stop opening Live after this many turns in one Pacific day. Text Gemini still runs. */
    const val DAILY_LIVE_TURN_CAP = 40

    const val COOLDOWN_SECONDS = 60L
    const val COOLDOWN_JITTER_SECONDS = 15L
    const val FOLLOW_UP_WINDOW_MS = 8_000L

    val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    const val SYSTEM_PROMPT = JarvisPrompt.SYSTEM
}
