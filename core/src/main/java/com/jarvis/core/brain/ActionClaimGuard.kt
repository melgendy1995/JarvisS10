package com.jarvis.core.brain

import com.jarvis.core.skills.AppControlPhrases
import com.jarvis.core.skills.Utterances
import java.util.regex.MatchResult
import java.util.regex.Pattern

/**
 * Detects device-action confirmation claims in cloud-brain replies the phone never made.
 * Pure so tests can feed the real sentences the brains have said. Nothing here reads or
 * writes the conversation; it classifies one reply text.
 */
object ActionClaimGuard {
    enum class Type {
        MEDIA_STOP,
        MEDIA_RESUME,
        MEDIA_NEXT,
        SHUFFLE,
        APP_OPEN,
        CAMERA,
        FLASHLIGHT,
        TIMER,
        VOLUME,
    }

    data class Claim(
        val type: Type,
        val arabic: Boolean,
        /** Camera/flashlight claims: true = claimed on, false = claimed off, null = not a state claim. */
        val on: Boolean? = null,
        /** Known app token from [AppControlPhrases] seen in the claim, null when unnamed. */
        val app: String? = null,
    ) {
        val id: String = type.name.lowercase()
    }

    fun detect(reply: String): Claim? {
        if (reply.isBlank()) return null
        val text = Utterances.normalize(reply)
        if (text.isEmpty()) return null
        arabicRules.firstOrNull { it.matches(text) }?.let { return it.claim(text) }
        return englishRules.firstOrNull { it.matches(text) }?.claim(text)
    }

    /**
     * The nearest honest phrasing for [claim], in the claim's own language:
     * no fiction, only the exact phrase the user should actually say.
     */
    fun honest(claim: Claim): String {
        val topic = when (claim.type) {
            Type.APP_OPEN -> Open
            Type.CAMERA -> if (claim.on != false) CameraOn else CameraOff
            Type.FLASHLIGHT -> if (claim.on != false) FlashOn else FlashOff
            Type.TIMER -> Timer
            else -> Media
        }
        return if (claim.arabic) topic.arabic else topic.english
    }

    /**
     * A command [AppControlPhrases] or a local skill can claim, rebuilt from the
     * claim itself. Null when the claim has no trustworthy mapping.
     */
    fun canonical(claim: Claim): String? = when (claim.type) {
        Type.MEDIA_STOP -> when (claim.app) {
            null -> "stop music"
            else -> "pause ${claim.app.replace('_', ' ')}"
        }
        Type.MEDIA_RESUME -> when (claim.app) {
            null -> "resume"
            else -> "resume on ${claim.app.replace('_', ' ')}"
        }
        Type.MEDIA_NEXT -> "next"
        Type.APP_OPEN -> claim.app?.let { "open ${it.replace('_', ' ')}" }
        Type.CAMERA -> if (claim.on == false) "turn off the camera" else "turn on the camera"
        Type.FLASHLIGHT -> if (claim.on == false) "turn off the flashlight" else "turn on the flashlight"
        Type.TIMER, Type.VOLUME, Type.SHUFFLE -> null
    }

    private data class Topic(val english: String, val arabic: String)

    private val Media = Topic(
        english = "I couldn't do that. Try saying 'pause YouTube Music'.",
        arabic = "ما قدرت أسويها. جرّب تقول: وقف يوتيوب ميوزك.",
    )
    private val Open = Topic(
        english = "I couldn't do that. Try saying 'open YouTube Music'.",
        arabic = "ما قدرت أسويها. جرّب تقول: افتح يوتيوب ميوزك.",
    )
    private val Timer = Topic(
        english = "I couldn't do that. Try saying 'set a timer for 5 minutes'.",
        arabic = "ما قدرت أسويها. جرّب تقول: اضبط مؤقت لخمس دقايق.",
    )
    private val CameraOn = Topic(
        english = "I couldn't do that. Try saying 'turn on the camera'.",
        arabic = "ما قدرت أسويها. جرّب تقول: شغل الكاميرا.",
    )
    private val CameraOff = Topic(
        english = "I couldn't do that. Try saying 'turn off the camera'.",
        arabic = "ما قدرت أسويها. جرّب تقول: اطفي الكاميرا.",
    )
    private val FlashOn = Topic(
        english = "I couldn't do that. Try saying 'turn on the flashlight'.",
        arabic = "ما قدرت أسويها. جرّب تقول: شغل الكشاف.",
    )
    private val FlashOff = Topic(
        english = "I couldn't do that. Try saying 'turn off the flashlight'.",
        arabic = "ما قدرت أسويها. جرّب تقول: اطفي الكشاف.",
    )

    /** Normalized app words -> [AppControlPhrases] target slot. Longest names first. */
    private fun appToken(words: String): String? {
        val token = words.trim().replace(Regex("\\s+"), " ")
        return when (token) {
            "youtube music", "yt music", "you tube music", "يوتيوب ميوزك", "يوتيوب ميوزيك" ->
                AppControlPhrases.TARGET_YOUTUBE_MUSIC
            "youtube", "you tube", "يوتيوب", "يوتوب", "اليوتيوب" -> AppControlPhrases.TARGET_YOUTUBE
            "spotify", "سبوتيفاي", "سبوتفاي" -> AppControlPhrases.TARGET_SPOTIFY
            "samsung music", "samsung", "سامسونج ميوزك", "سامسونج" -> AppControlPhrases.TARGET_SAMSUNG
            else -> null
        }
    }

    /** Longest-first scan of a claim fragment for a known app token. */
    private fun appFromText(fragment: String): String? {
        val tokens = listOf(
            "youtube music", "yt music", "you tube music", "spotify", "samsung music", "samsung", "youtube",
            "يوتيوب ميوزك", "يوتيوب ميوزيك", "يو تيوب ميوزك", "يوتيوب", "يوتوب", "سبوتيفاي",
            "سامسونج ميوزك", "سامسونج",
        )
        return tokens.firstOrNull { fragment.contains(it) }?.let { appToken(it) }
    }

    private class Rule(
        val type: Type,
        val arabic: Boolean,
        pattern: String,
        val onOf: (MatchResult) -> Boolean? = { null },
        val appOf: (MatchResult) -> String? = { null },
    ) {
        // Unicode word classes so \b also acts on Arabic letters.
        private val regex = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)

        fun matches(text: String): Boolean = regex.matcher(text).find()

        fun claim(text: String): Claim {
            val matcher = regex.matcher(text)
            check(matcher.find()) { "claim is only called on a matching rule" }
            return Claim(type, arabic, onOf(matcher), appOf(matcher))
        }
    }

    // English rules. Windows are short so a story spanning sentences cannot match.
    private val englishRules = listOf(
        Rule(
            Type.MEDIA_STOP,
            arabic = false,
            pattern = "\\bi\\b[^؟]{0,60}\\b(?:stopped|paused|resumed|shut)\\b[^؟]{0,30}\\b(?:the\\s+)?(?:music|media|playback|song|video|track|player|youtube(?:\\s+music)?|spotify)\\b",
        ),
        Rule(
            Type.MEDIA_STOP,
            arabic = false,
            pattern = "\\b(?:(youtube(?:\\s+music)?|spotify|samsung(?:\\s+music)?|yt music|music|media|playback|song|video|track|player)\\s+)(?:is|are|was|were|has|have|got)\\s+(?:been\\s+|now\\s+|just\\s+)*(?:stopped|paused)\\b",
            appOf = { m -> appFromText(m.group()) },
        ),
        Rule(
            Type.MEDIA_RESUME,
            arabic = false,
            pattern = "\\b(?:(youtube(?:\\s+music)?|spotify|yt music)\\s+)(?:is|has)\\s+(?:been\\s+|now\\s+)*playing\\b|\\bplaying\\s+(?:now|for\\s+you)\\b|\\bi\\b[^؟]{0,50}\\b(?:resumed|started)\\b[^؟]{0,20}\\b(?:the\\s+)?(?:music|media|playback|song|video)\\b",
            appOf = { m -> appFromText(m.group()) },
        ),
        Rule(
            Type.MEDIA_NEXT,
            arabic = false,
            pattern = "\\bi\\b[^؟]{0,50}\\b(?:skipped|switched)\\b[^؟]{0,20}\\b(?:to\\s+)?(?:the\\s+)?(?:next)\\b",
        ),
        Rule(
            Type.APP_OPEN,
            arabic = false,
            pattern = "\\b(?:opening|opened|launched|launching|loading)\\s+(?:(?:youtube(?:\\s+music)?|spotify|samsung(?:\\s+music)?|yt music)\\b|(?:the\\s+)?\\w+\\s+app)",
            appOf = { m -> appFromText(m.group()) },
        ),
        Rule(
            Type.SHUFFLE,
            arabic = false,
            pattern = "\\bshuffle(?:\\s+mode)?\\s+(?:is\\s+)?(?:now\\s+)?(?:on|active|enabled)\\b|\\bi\\b[^؟]{0,50}\\b(?:shuffled|enabled\\s+shuffle)\\b",
        ),
        Rule(
            Type.CAMERA,
            arabic = false,
            pattern = "\\b(?:camera|torch|the\\s+camera)\\s+(?:is|was|has\\s+been)\\s+(?:now\\s+)?(on|off|open|running|enabled)\\b",
            onOf = { m ->
                m.group(1).let { it == "on" || it == "open" || it == "running" || it == "enabled" }
            },
        ),
        Rule(
            Type.FLASHLIGHT,
            arabic = false,
            pattern = "\\b(?:flashlight|torch)\\s+(?:is|was|has\\s+been)\\s+(?:now\\s+)?(on|off|open|running|enabled)\\b|\\bi\\b[^؟]{0,50}\\b(?:turned|switched)\\b[^؟]{0,20}\\b(?:on|off)\\b[^؟]{0,20}\\b(?:the\\s+)?(?:flashlight|torch)\\b",
            onOf = { m ->
                if (m.groupCount() >= 1) {
                    m.group(1)?.let { it == "on" || it == "open" || it == "running" || it == "enabled" }
                } else {
                    null
                }
            },
        ),
        Rule(
            Type.TIMER,
            arabic = false,
            pattern = "\\btimer\\s+(?:is\\s+)?(?:now\\s+)?(?:set|running|going|counting)\\b|\\bi\\b[^؟]{0,50}\\b(?:set|started)\\b[^؟]{0,20}\\b(?:the\\s+)?timer\\b",
        ),
        Rule(
            Type.VOLUME,
            arabic = false,
            pattern = "\\bi\\b[^؟]{0,50}\\b(?:set|adjusted|raised|lowered|turned)\\b[^؟]{0,20}\\b(?:the\\s+)?volume\\b|\\bvolume\\s+(?:is|has\\s+been)\\s+(?:now\\s+)?(?:set|adjusted)\\b",
        ),
    )

    // Arabic rules, on the Utterances-normalized alphabet (ة→ه, أ→ا, ؤ→و).
    private val arabicRules = listOf(
        Rule(
            Type.MEDIA_STOP,
            arabic = true,
            pattern = "\\bتم (?:الايقاف|الاطفاء|الاغلاق)(?!\\s*\\p{L})|\\b(?:الايقاف|الاطفاء)\\s+(?:يوتيوب ميوزك|يوتيوب|سبوتيفاي|سامسونج ميوزك)\\b|\\b(?:وقفت|اوقفت)\\b[^؟]{0,40}\\b(?:الموسيقي|التشغيل|الاغنيه|الاغاني|الفيديو|يوتيوب ميوزك|يوتيوب|يوتوب|سبوتيفاي|سامسونج ميوزك|الصوت|التطبيق|الجهاز)\\b",
            appOf = { m -> appFromText(m.group()) },
        ),
        Rule(
            Type.APP_OPEN,
            arabic = true,
            pattern = "\\b(?:فتحت|افتحت)\\s*(?:يوتيوب ميوزك|يوتيوب|سبوتيفاي|التطبيق|الكاميرا)",
            appOf = { m -> appFromText(m.group()) },
        ),
        Rule(
            Type.MEDIA_RESUME,
            arabic = true,
            pattern = "\\bتم التشغيل(?!\\s*\\p{L})|\\b(?:شغلت|حطيت|تشغلت)\\b[^؟]{0,40}\\b(?:الموسيقي|التشغيل|الاغنيه|الاغاني|الفيديو|يوتيوب ميوزك|يوتيوب|يوتوب|سبوتيفاي|الجهاز|التطبيق|الكاميرا|الكشاف)\\b",
            appOf = { m -> appFromText(m.group()) },
        ),
        Rule(
            Type.CAMERA,
            arabic = true,
            pattern = "\\b(?:الكاميرا|الفلاش)\\s+(شغاله|شغالة|تعمل|يعمل|شغله|مطفيه|مطفي|مقفوله)\\b|\\b(?:شغلت|فتحت|اطفيت)\\s+(?:الكاميرا|الفلاش)\\b",
            onOf = { m ->
                val w = if (m.groupCount() >= 1) m.group(1) ?: "" else ""
                when {
                    w.isEmpty() -> null
                    w.startsWith("م") -> false
                    else -> true
                }
            },
        ),
        Rule(
            Type.FLASHLIGHT,
            arabic = true,
            pattern = "\\b(?:الكشاف|الفلاش)\\s+(شغال|شغاله|تعمل|يعمل|مطفي|مطفيه|مفتوح)\\b",
            onOf = { m -> !m.group(1).orEmpty().startsWith("م") },
        ),
        Rule(
            Type.TIMER,
            arabic = true,
            pattern = "\\b(?:ضبطت الموقت|تم ضبط الموقت|الموقت (?:شغال|يعمل|تم ضبطه))\\b",
        ),
        Rule(
            Type.VOLUME,
            arabic = true,
            pattern = "\\b(?:ضبطت|رفعت|خفضت|كتمت)\\s+الصوت\\b|\\bتم\\s+(?:رفع|خفض|كتم)\\s+الصوت\\b",
        ),
    )
}
