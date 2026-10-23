package com.jarvis.provider.gemini

import java.time.Duration

/** Reads 429 / RESOURCE_EXHAUSTED / quota out of Firebase exceptions without depending on their types. */
object QuotaSignals {
    fun isRateLimit(error: Throwable): Boolean {
        if (chain(error).any { it.javaClass.name.endsWith("QuotaExceededException") }) return true
        val text = messages(error)
        return text.contains("quota", ignoreCase = true) ||
            text.contains("resource_exhausted", ignoreCase = true) ||
            text.contains("resource exhausted", ignoreCase = true) ||
            HTTP_429.containsMatchIn(text)
    }

    fun isNotConfigured(error: Throwable): Boolean {
        if (chain(error).any { throwable ->
                val name = throwable.javaClass.name
                name.endsWith("ServiceDisabledException") ||
                    name.endsWith("InvalidAPIKeyException") ||
                    name.endsWith("APINotConfiguredException")
            }
        ) {
            return true
        }
        val text = messages(error)
        return text.contains("genai config not found", ignoreCase = true) ||
            text.contains("not enabled", ignoreCase = true) ||
            text.contains("api key not valid", ignoreCase = true) ||
            text.contains("firebase app is not initialized", ignoreCase = true)
    }

    fun isDailyCap(message: String): Boolean {
        val text = message.lowercase()
        return text.contains("per day") ||
            text.contains("per-day") ||
            text.contains("perday") ||
            text.contains("daily") ||
            text.contains("/day") ||
            text.contains("rpd")
    }

    /** Retry-After seconds, a retryDelay like `30s`, or "retry after N seconds". */
    fun retryAfter(error: Throwable): Duration? = retryAfter(messages(error))

    fun retryAfter(message: String): Duration? {
        RETRY_AFTER_SECONDS.find(message)?.let { match ->
            return Duration.ofSeconds(match.groupValues[1].toLong())
        }
        RETRY_DELAY.find(message)?.let { match ->
            val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
            return Duration.ofMillis((seconds * 1000).toLong())
        }
        RETRY_PHRASE.find(message)?.let { match ->
            val count = match.groupValues[1].toLong()
            val unit = match.groupValues[2].lowercase()
            val seconds = if (unit.startsWith("m")) count * 60 else count
            return Duration.ofSeconds(seconds)
        }
        return null
    }

    fun messages(error: Throwable): String {
        return chain(error).mapNotNull { it.message }.joinToString("\n")
    }

    private fun chain(error: Throwable): Sequence<Throwable> {
        return generateSequence(error) { it.cause }
    }

    private val HTTP_429 = Regex("""(^|[^0-9])429([^0-9]|$)""")
    private val RETRY_AFTER_SECONDS = Regex(
        """retry-after["']?\s*[:=]\s*(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val RETRY_DELAY = Regex(
        """retryDelay["']?\s*[:=]\s*["']?(\d+(?:\.\d+)?)s""",
        RegexOption.IGNORE_CASE,
    )
    private val RETRY_PHRASE = Regex(
        """retry (?:in|after)\s+(\d+)\s*(seconds?|secs?|s|minutes?|mins?|m)\b""",
        RegexOption.IGNORE_CASE,
    )
}
