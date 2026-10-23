package com.jarvis.provider.gemini

import java.time.ZoneId

/** Model ids and client caps. Change them here, nowhere else. */
object ModelConfig {
    const val LIVE_MODEL = "gemini-3.1-flash-live-preview"
    const val TEXT_MODEL = "gemini-3.1-flash-lite"

    const val INPUT_SAMPLE_RATE_HZ = 16_000
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000

    /** Stop opening Live after this many turns in one Pacific day. Text Gemini still runs. */
    const val DAILY_LIVE_TURN_CAP = 40

    const val COOLDOWN_SECONDS = 60L
    const val COOLDOWN_JITTER_SECONDS = 15L
    const val FOLLOW_UP_WINDOW_MS = 8_000L

    val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    const val SYSTEM_PROMPT =
        "You are Jarvis, Mahmoud's voice assistant on a phone. " +
            "Answer in one or two short spoken sentences. " +
            "Use the time, timer, and flashlight tools when the user asks for those. " +
            "Do not invent tool results."
}
