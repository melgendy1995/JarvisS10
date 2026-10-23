package com.jarvis.provider.gemini

/**
 * After Live fails, skip it for [windowMs] so the voice loop can reach Gemini text and Groq
 * without waiting on another socket.
 */
class LiveCircuit(
    private val windowMs: Long = WINDOW_MS,
) {
    @Volatile private var failedAtMs: Long = 0L

    fun skip(nowMs: Long): Boolean {
        val failed = failedAtMs
        return failed != 0L && nowMs - failed in 0 until windowMs
    }

    fun mark(nowMs: Long) {
        failedAtMs = nowMs
    }

    fun clear() {
        failedAtMs = 0L
    }

    companion object {
        const val WINDOW_MS = 5 * 60_000L
    }
}
