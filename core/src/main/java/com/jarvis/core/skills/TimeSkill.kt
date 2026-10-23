package com.jarvis.core.skills

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun interface WallClock {
    fun now(): ZonedDateTime
}

class TimeSkill(
    private val clock: WallClock = WallClock { ZonedDateTime.now() },
) : Skill {
    override val id: String = "time"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        val kind = when {
            normalized in TIME_AR -> "time"
            normalized in DATE_AR -> "date"
            TIME_EN.matches(normalized) -> "time"
            DATE_EN.matches(normalized) -> "date"
            else -> return null
        }
        val lang = if (normalized.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        return SkillCall(
            skillId = id,
            utterance = text,
            slots = mapOf("kind" to kind, "lang" to lang),
        )
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Get the current local time or today's date.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "kind" to mapOf(
                    "type" to "string",
                    "enum" to listOf("time", "date"),
                ),
            ),
            "required" to listOf("kind"),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val now = clock.now()
        val arabic = call.slots["lang"] == "ar"
        val date = call.slots["kind"] == "date"
        val text = when {
            arabic && date -> "اليوم ${now.format(AR_DATE)}."
            arabic -> "الساعة ${now.format(AR_TIME)}."
            date -> "It's ${now.format(EN_DATE)}."
            else -> "It's ${now.format(EN_TIME)}."
        }
        return SkillResult(text = text)
    }

    private companion object {
        val EN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val EN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
        val AR_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale("ar"))
        val AR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale("ar"))

        val TIME_EN = Regex(
            """^(?:whats the time|what is the time|what time is it|tell me the time|current time|time now|the time|time)(?: now| please)?$""",
        )
        val DATE_EN = Regex(
            """^(?:whats the date|what is the date|whats todays date|what is todays date|what is today|whats today|what day is it|tell me the date|current date|todays date|the date|date)(?: today| please| now)?$""",
        )
        val TIME_AR = setOf(
            "كم الساعه",
            "كم الساعه الان",
            "ما الوقت",
            "ما الوقت الان",
            "ما هو الوقت",
            "ما هو الوقت الان",
            "الساعه كم",
            "كم الوقت",
            "الوقت",
            "الساعه",
            "قول لي الساعه",
        )
        val DATE_AR = setOf(
            "ما التاريخ",
            "ما هو التاريخ",
            "ما تاريخ اليوم",
            "التاريخ اليوم",
            "اي يوم هذا",
            "اي يوم اليوم",
            "ما هو اليوم",
            "ما اليوم",
            "التاريخ",
            "قول لي التاريخ",
        )
    }
}
