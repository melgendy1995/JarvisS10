package com.jarvis.provider.gemini

import java.time.Duration

data class QuotaReport(
    val httpStatus: Int?,
    val status: String?,
    val quotaId: String?,
    val metric: String?,
    /** "minute", "day", or null when the body does not say. */
    val window: String?,
    val model: String?,
    val limit: String?,
)

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

    /** Quota id, window, and model from a Gemini error body. Missing fields stay null. */
    fun report(message: String): QuotaReport {
        val http = HTTP_STATUS.find(message)?.groupValues?.get(1)?.toIntOrNull()
            ?: JSON_CODE.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val status = JSON_STATUS.find(message)?.groupValues?.get(1)
        val quotaId = JSON_QUOTA_ID.find(message)?.groupValues?.get(1)
        val metric = JSON_METRIC.find(message)?.groupValues?.get(1)
            ?: METRIC_LABEL.find(message)?.groupValues?.get(1)?.trimEnd(',', '.')
        val model = MODEL_LABEL.find(message)?.groupValues?.get(1)
            ?: JSON_MODEL.find(message)?.groupValues?.get(1)
        val limit = JSON_LIMIT.find(message)?.groupValues?.get(1)
            ?: LIMIT_LABEL.find(message)?.groupValues?.get(1)
        return QuotaReport(
            httpStatus = http,
            status = status,
            quotaId = quotaId,
            metric = metric,
            window = window(message, quotaId),
            model = model,
            limit = limit,
        )
    }

    fun failureLine(model: String, error: Throwable): String {
        val parsed = report(messages(error))
        return "grounded failure model=$model http=${parsed.httpStatus ?: "none"} " +
            "status=${parsed.status ?: "none"} quotaId=${parsed.quotaId ?: "none"} " +
            "metric=${parsed.metric ?: "none"} window=${parsed.window ?: "none"} " +
            "limit=${parsed.limit ?: "none"} quotaModel=${parsed.model ?: "none"}"
    }

    /** Redacted error text, one log line, long enough to keep the quota JSON. */
    fun failureBody(error: Throwable): String {
        val text = redact(messages(error)).replace(Regex("""\s+"""), " ").trim()
        if (text.length <= BODY_CAP) return text
        return text.take(BODY_CAP) + " truncated"
    }

    private fun window(message: String, quotaId: String?): String? {
        val text = message.lowercase()
        val id = quotaId?.lowercase().orEmpty()
        val minute = text.contains("per minute") || text.contains("per-minute") ||
            text.contains("perminute") || text.contains("/minute") || text.contains("rpm") ||
            id.contains("perminute")
        val day = text.contains("per day") || text.contains("per-day") ||
            text.contains("perday") || text.contains("/day") || text.contains("rpd") ||
            text.contains("daily") || id.contains("perday")
        return when {
            minute && !day -> "minute"
            day && !minute -> "day"
            day && minute -> "day"
            else -> null
        }
    }

    private fun chain(error: Throwable): Sequence<Throwable> {
        return generateSequence(error) { it.cause }
    }

    private const val BODY_CAP = 3000
    private val HTTP_429 = Regex("""(^|[^0-9])429([^0-9]|$)""")
    private val HTTP_STATUS = Regex("""\[(\d{3})]""")
    private val JSON_CODE = Regex(""""code"\s*:\s*(\d{3})""")
    private val JSON_STATUS = Regex(""""status"\s*:\s*"([A-Z_]+)"""")
    private val JSON_QUOTA_ID = Regex(""""quotaId"\s*:\s*"([^"]+)"""")
    private val JSON_METRIC = Regex(""""quotaMetric"\s*:\s*"([^"]+)"""")
    private val METRIC_LABEL = Regex("""metric:\s*([A-Za-z0-9_./-]+)""")
    private val MODEL_LABEL = Regex("""model:\s*(gemini-[A-Za-z0-9.\-]*[A-Za-z0-9])""")
    private val JSON_MODEL = Regex(""""model"\s*:\s*"(gemini-[^"]+)"""")
    private val JSON_LIMIT = Regex(""""quotaValue"\s*:\s*"?(\d+)""")
    private val LIMIT_LABEL = Regex("""limit:\s*(\d+)""")
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
