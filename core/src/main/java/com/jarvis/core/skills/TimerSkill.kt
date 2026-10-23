package com.jarvis.core.skills

fun interface TimerHandle {
    fun cancel()
}

/**
 * Schedules a one-shot announcement. The Android side uses an in-process delay
 * plus AlarmManager so a killed process can still speak.
 */
interface TimerScheduler {
    fun schedule(delayMillis: Long, message: String): TimerHandle

    /** @return how many timers were cancelled. */
    fun cancelAll(): Int
}

class TimerSkill(
    private val scheduler: TimerScheduler,
) : Skill {
    override val id: String = "timer"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        if (CANCEL_EN.matches(normalized) || normalized in CANCEL_AR) {
            return call(text, normalized, mapOf("action" to "cancel"))
        }
        SET_EN.matchEntire(normalized)?.let { match ->
            return durationOrAsk(text, normalized, match.groupValues[1])
        }
        SET_SHORT.matchEntire(normalized)?.let { match ->
            return durationOrAsk(text, normalized, match.groupValues[1])
        }
        SET_EMBEDDED.matchEntire(normalized)?.let { match ->
            return durationCall(text, normalized, match.groupValues[1])
        }
        SET_AR.matchEntire(normalized)?.let { match ->
            return durationOrAsk(text, normalized, match.groupValues[1])
        }
        return null
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Set or cancel a countdown timer on the phone.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("set", "cancel")),
                "duration_ms" to mapOf("type" to "integer"),
            ),
            "required" to listOf("action"),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        when (call.slots["action"]) {
            "cancel" -> {
                val count = scheduler.cancelAll()
                val arabic = call.slots["lang"] == "ar"
                val text = when {
                    count == 0 && arabic -> "ما في مؤقت شغال."
                    count == 0 -> "No timer is running."
                    arabic -> "تم إلغاء المؤقت."
                    count == 1 -> "Timer cancelled."
                    else -> "Timers cancelled."
                }
                return SkillResult(text = text, success = count > 0)
            }
            "missing" -> {
                val text = if (call.slots["lang"] == "ar") "لمده كم؟" else "For how long?"
                return SkillResult(text = text, success = false)
            }
        }
        val millis = call.slots["millis"]?.toLongOrNull()
            ?: return SkillResult(text = "For how long?", success = false)
        val message = call.slots["done"] ?: "Timer done."
        scheduler.schedule(millis, message)
        val confirm = call.slots["confirm"] ?: "Timer set."
        return SkillResult(text = confirm)
    }

    private fun durationOrAsk(utterance: String, normalized: String, rest: String): SkillCall {
        return durationCall(utterance, normalized, rest)
            ?: call(utterance, normalized, mapOf("action" to "missing"))
    }

    private fun durationCall(utterance: String, normalized: String, rest: String): SkillCall? {
        val millis = Durations.parseMillis(rest) ?: return null
        val arabic = normalized.any { it in '\u0600'..'\u06FF' }
        val confirm = if (arabic) {
            "تم ضبط المؤقت لمدة ${Durations.formatArabic(millis)}."
        } else {
            "Timer set for ${Durations.formatEnglish(millis)}."
        }
        val done = if (arabic) "انتهى المؤقت." else "Timer done."
        return call(
            utterance,
            normalized,
            mapOf(
                "action" to "set",
                "millis" to millis.toString(),
                "confirm" to confirm,
                "done" to done,
            ),
        )
    }

    private fun call(utterance: String, normalized: String, slots: Map<String, String>): SkillCall {
        val lang = if (normalized.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        return SkillCall(skillId = id, utterance = utterance, slots = slots + ("lang" to lang))
    }

    private companion object {
        val CANCEL_EN = Regex(
            """^(?:please\s+)?(?:cancel|stop|clear|delete|remove)(?:\s+(?:the|my|all))?\s+timers?(?:\s+please)?$""",
        )
        // Spelling is after Utterances.normalize (ؤ→و, ئ→ي, ة→ه).
        val CANCEL_AR = setOf(
            "الغ الموقت",
            "الغي الموقت",
            "اوقف الموقت",
            "وقف الموقت",
            "ايقاف الموقت",
            "الغ التايمر",
        )
        val SET_EN = Regex(
            """^(?:please\s+)?(?:can you\s+)?(?:set|start|create)\s+(?:a\s+|an\s+|the\s+)?(?:timer|countdown)(?:\s+(?:for|of|in))?\s*(.*)$""",
        )
        val SET_SHORT = Regex(
            """^(?:a\s+)?(?:timer|countdown)(?:\s+(?:for|of|in))?\s+(.+)$""",
        )
        val SET_EMBEDDED = Regex(
            """^(?:please\s+)?(?:can you\s+)?(?:set|start|create)\s+(?:a\s+|an\s+)?(.+?)\s+(?:timer|countdown)$""",
        )
        val SET_AR = Regex(
            """^(?:من فضلك\s+)?(?:اضبط|ابدا|شغل)?\s*(?:موقت|تايمر)(?:\s+لمده)?\s*(.*)$""",
        )
    }
}

internal object Durations {
    private val words = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val arabicWords = mapOf(
        "واحد" to 1, "واحده" to 1,
        "اثنين" to 2, "اثنتين" to 2,
        "ثلاث" to 3, "ثلاثه" to 3,
        "اربع" to 4, "اربعه" to 4,
        "خمس" to 5, "خمسه" to 5,
        "ست" to 6, "سته" to 6,
        "سبع" to 7, "سبعه" to 7,
        "ثمان" to 8, "ثماني" to 8, "ثمانيه" to 8,
        "تسع" to 9, "تسعه" to 9,
        "عشر" to 10, "عشره" to 10,
    )

    private val english = Regex(
        """(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:\s+(one|two|three|four|five|six|seven|eight|nine))?\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val arabic = Regex(
        """(\d+|ثمانيه|ثماني|ثمان|تسعه|تسع|سبعه|سبع|سته|ست|خمسه|خمس|اربعه|اربع|ثلاثه|ثلاث|اثنتين|اثنين|عشره|عشر|واحده|واحد)\s*(ثانيه|ثواني|ثوان|دقيقه|دقايق|دقائق|ساعه|ساعات)""",
    )

    private const val MAX_MILLIS = 24L * 60L * 60L * 1000L

    fun parseMillis(raw: String): Long? {
        var text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        var total = 0L
        if (text.contains("half an hour") || text.contains("half hour") || text.contains("نصف ساعه")) {
            total += 30L * 60_000L
            text = text.replace("half an hour", " ")
                .replace("half hour", " ")
                .replace("نصف ساعه", " ")
        }
        text = text.replace(Regex("""\b(?:an|a)\s+hour\b"""), "1 hour")
            .replace(Regex("""\b(?:an|a)\s+minute\b"""), "1 minute")
            .replace(Regex("""\b(?:an|a)\s+second\b"""), "1 second")

        var found = false
        for (match in english.findAll(text)) {
            val value = number(match.groupValues[1], match.groupValues[2]) ?: continue
            found = true
            total += value * unitMillis(match.groupValues[3])
        }
        for (match in arabic.findAll(text)) {
            val value = arabicNumber(match.groupValues[1]) ?: continue
            found = true
            total += value * arabicUnitMillis(match.groupValues[2])
        }
        if (!found) {
            val bare = Regex(
                """\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty)\b""",
                RegexOption.IGNORE_CASE,
            ).find(text)
            if (bare != null) {
                val value = number(bare.groupValues[1], "") ?: return null
                total += value * 60_000L
            } else if (total == 0L) {
                return null
            }
        }
        if (total <= 0L) return null
        return total.coerceIn(1_000L, MAX_MILLIS)
    }

    fun formatEnglish(millis: Long): String {
        var seconds = millis / 1000L
        val hours = seconds / 3600L
        seconds %= 3600L
        val minutes = seconds / 60L
        seconds %= 60L
        val parts = ArrayList<String>(3)
        if (hours > 0) parts += if (hours == 1L) "1 hour" else "$hours hours"
        if (minutes > 0) parts += if (minutes == 1L) "1 minute" else "$minutes minutes"
        if (seconds > 0 || parts.isEmpty()) {
            parts += if (seconds == 1L) "1 second" else "$seconds seconds"
        }
        return parts.joinToString(" and ")
    }

    fun formatArabic(millis: Long): String {
        var seconds = millis / 1000L
        val hours = seconds / 3600L
        seconds %= 3600L
        val minutes = seconds / 60L
        seconds %= 60L
        val parts = ArrayList<String>(3)
        if (hours > 0) parts += "$hours ساعه"
        if (minutes > 0) parts += "$minutes دقيقه"
        if (seconds > 0 || parts.isEmpty()) parts += "$seconds ثانيه"
        return parts.joinToString(" و ")
    }

    private fun number(head: String, tail: String): Long? {
        val base = head.toLongOrNull() ?: words[head.lowercase()]?.toLong() ?: return null
        if (tail.isBlank()) return base
        val extra = words[tail.lowercase()] ?: return null
        return base + extra
    }

    private fun arabicNumber(token: String): Long? {
        return token.toLongOrNull() ?: arabicWords[token]?.toLong()
    }

    private fun unitMillis(unit: String): Long = when (unit.lowercase()) {
        "h", "hr", "hrs", "hour", "hours" -> 3_600_000L
        "m", "min", "mins", "minute", "minutes" -> 60_000L
        else -> 1_000L
    }

    private fun arabicUnitMillis(unit: String): Long = when (unit) {
        "ساعه", "ساعات" -> 3_600_000L
        "دقيقه", "دقايق", "دقائق" -> 60_000L
        else -> 1_000L
    }
}
