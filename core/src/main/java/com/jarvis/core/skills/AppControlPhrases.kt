package com.jarvis.core.skills

/**
 * Offline phrases for launch, playback, transport, and volume.
 * Patterns run on [Utterances.normalize] (أإآ→ا, ة→ه, ى→ي).
 */
object AppControlPhrases {
    const val OPEN = "open"
    const val PLAY = "play"
    const val MEDIA = "media"
    const val VOLUME = "volume"
    const val SKIP_AD = "skip_ad"
    const val RETURN = "return"

    const val TARGET_YOUTUBE = "youtube"
    const val TARGET_YOUTUBE_MUSIC = "youtube_music"
    const val TARGET_SPOTIFY = "spotify"
    const val TARGET_SAMSUNG = "samsung"

    fun parse(raw: String): Map<String, String>? {
        var text = Utterances.normalize(raw)
        if (text.isEmpty()) return null
        text = stripWake(text)
        if (text.isEmpty()) return null
        val lang = if (text.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        // % is punctuation and normalize drops it. Look at the raw line too.
        val wantsPercent = raw.contains('%') || raw.contains('٪') ||
            raw.contains("percent", ignoreCase = true) ||
            text.contains("بالميه") || text.contains("بالمئه") || text.contains("في الميه")
        skipAd(text)?.let { return it + langSlot(lang) }
        returning(text)?.let { return it + langSlot(lang) }
        volume(text, wantsPercent)?.let { return it + langSlot(lang) }
        media(text)?.let { return it + langSlot(lang) }
        play(text)?.let { return it + langSlot(lang) }
        open(text)?.let { return it + langSlot(lang) }
        return null
    }

    private fun langSlot(lang: String) = mapOf("lang" to lang)

    private fun stripWake(text: String): String {
        val prefixes = listOf(
            "hey jarvis ",
            "ok jarvis ",
            "jarvis ",
            "يا جارفس ",
            "يا جارفيس ",
        )
        var current = text
        for (prefix in prefixes) {
            if (current.startsWith(prefix)) current = current.removePrefix(prefix).trim()
        }
        return current
    }

    private fun skipAd(text: String): Map<String, String>? {
        if (text in SKIP_EXACT || SKIP_EN.matches(text) || SKIP_AR.matches(text)) {
            return mapOf("action" to SKIP_AD)
        }
        return null
    }

    private fun returning(text: String): Map<String, String>? {
        if (RETURN_EN.matches(text) || text in RETURN_AR) return mapOf("action" to RETURN)
        return null
    }

    private fun volume(text: String, wantsPercent: Boolean): Map<String, String>? {
        if (text in VOLUME_UP || VOLUME_UP_EN.matches(text)) {
            return mapOf("action" to VOLUME, "volume" to "up")
        }
        if (text in VOLUME_DOWN || VOLUME_DOWN_EN.matches(text)) {
            return mapOf("action" to VOLUME, "volume" to "down")
        }
        if (text in MUTE || MUTE_EN.matches(text)) {
            return mapOf("action" to VOLUME, "volume" to "mute")
        }
        if (text in UNMUTE || UNMUTE_EN.matches(text)) {
            return mapOf("action" to VOLUME, "volume" to "unmute")
        }
        val set = VOLUME_SET.matchEntire(text) ?: VOLUME_SET_AR.matchEntire(text) ?: return null
        val amount = set.groupValues[1]
        val unitWord = set.groupValues.getOrNull(2).orEmpty()
        val unit = if (wantsPercent || unitWord.isNotBlank()) "percent" else "auto"
        return mapOf("action" to VOLUME, "volume" to "set", "amount" to amount, "unit" to unit)
    }

    private fun media(text: String): Map<String, String>? {
        val which = when {
            text in PAUSE || PAUSE_EN.matches(text) -> "pause"
            text in RESUME || RESUME_EN.matches(text) -> "resume"
            text in NEXT || NEXT_EN.matches(text) -> "next"
            text in PREVIOUS || PREVIOUS_EN.matches(text) -> "previous"
            else -> return null
        }
        return mapOf("action" to MEDIA, "media" to which)
    }

    private fun play(text: String): Map<String, String>? {
        val targeted = PLAY_ON.matchEntire(text)
        if (targeted != null) {
            val query = targeted.groupValues[1].trim()
            val target = targetOf(targeted.groupValues[2].trim()) ?: return null
            if (query.isEmpty()) return null
            transportQuery(query)?.let { return mapOf("action" to MEDIA, "media" to it) }
            return mapOf("action" to PLAY, "query" to query, "target" to target)
        }
        val plain = PLAY_QUERY.matchEntire(text) ?: return null
        val query = plain.groupValues[1].trim()
        if (query.isEmpty()) return null
        transportQuery(query)?.let { return mapOf("action" to MEDIA, "media" to it) }
        return mapOf("action" to PLAY, "query" to query, "target" to "")
    }

    private fun open(text: String): Map<String, String>? {
        val match = OPEN_EN.matchEntire(text) ?: OPEN_AR.matchEntire(text) ?: return null
        val name = match.groupValues[1].trim()
        if (name.isEmpty() || name.length > 80 || name.split(' ').size > 4) return null
        if (EXCLUDED_OPEN.containsMatchIn(name)) return null
        return mapOf("action" to OPEN, "target" to name)
    }

    private fun transportQuery(query: String): String? = when (query) {
        "next", "the next", "the next one", "next song", "next video", "next track", "التالي" -> "next"
        "previous", "the previous", "previous song", "previous video", "last song", "السابق" -> "previous"
        else -> null
    }

    private fun targetOf(token: String): String? = when (token) {
        "youtube", "you tube", "يوتيوب", "يوتوب", "اليوتيوب" -> TARGET_YOUTUBE
        "youtube music", "yt music", "يوتيوب ميوزك", "يوتيوب ميوزيك" -> TARGET_YOUTUBE_MUSIC
        "spotify", "سبوتيفاي", "سبوتفاي" -> TARGET_SPOTIFY
        "samsung music", "samsung", "سامسونج ميوزك", "سامسونج" -> TARGET_SAMSUNG
        else -> null
    }

    private val SKIP_EXACT = setOf(
        "skip ad",
        "skip the ad",
        "skip ads",
        "skip this ad",
        "تخطي الاعلان",
        "تخطي الاعلانات",
        "سكيب الاعلان",
    )
    private val SKIP_EN = Regex("""^skip(?:\s+the|\s+this)?\s+ads?$""")
    private val SKIP_AR = Regex("""^(?:تخطي|سكيب)\s+الاعلان(?:ات)?$""")

    private val RETURN_EN = Regex(
        """^(?:please\s+)?(?:stop|go back(?:\s+to\s+jarvis)?|back to jarvis|return(?:\s+to\s+jarvis)?)$""",
    )
    private val RETURN_AR = setOf(
        "ارجع",
        "رجوع",
        "ارجع لجارفس",
        "ارجع لجارفيس",
        "ارجع يا جارفس",
        "ارجع يا جارفيس",
        "ارجع الي جارفس",
        "ارجع الي جارفيس",
        "ارجع للتطبيق",
        "ارجع ل جارفس",
    )

    private val VOLUME_UP = setOf(
        "ارفع الصوت",
        "علي الصوت",
        "عالي الصوت",
        "زود الصوت",
        "صوت اعلي",
        "الصوت اعلي",
        "ارفع صوت",
    )
    private val VOLUME_DOWN = setOf(
        "وطي الصوت",
        "وط الصوت",
        "اخفض الصوت",
        "نزل الصوت",
        "خفض الصوت",
        "صوت اقل",
        "الصوت اقل",
    )
    private val MUTE = setOf("كتم", "اكتم", "كتم الصوت", "اكتم الصوت", "صامت", "اسكت الصوت")
    private val UNMUTE = setOf("فك الكتم", "الغ الكتم", "الغي الكتم", "فتح الصوت", "شغل الصوت")

    private val VOLUME_UP_EN = Regex(
        """^(?:please\s+)?(?:volume up|turn it up|turn the volume up|raise the volume|increase the volume|louder|volume higher)$""",
    )
    private val VOLUME_DOWN_EN = Regex(
        """^(?:please\s+)?(?:volume down|turn it down|turn the volume down|lower the volume|decrease the volume|quieter|volume lower)$""",
    )
    private val MUTE_EN = Regex("""^(?:please\s+)?(?:mute|mute the volume|silence|mute music)$""")
    private val UNMUTE_EN = Regex("""^(?:please\s+)?(?:unmute|unmute the volume|unmute music)$""")
    private val VOLUME_SET = Regex(
        """^(?:please\s+)?(?:set\s+)?(?:the\s+)?volume(?:\s+level)?\s+(?:to\s+|at\s+)?(\d{1,3})\s*(percent|%)?$""",
    )
    private val VOLUME_SET_AR = Regex(
        """^(?:خلي الصوت|اضبط الصوت|ضبط الصوت|الصوت|صوت)\s+(\d{1,3})\s*(?:بالميه|بالمئه|في الميه|%)?$""",
    )

    private val PAUSE = setOf(
        "وقف",
        "اوقف",
        "توقف",
        "ايقاف",
        "ايقاف موقت",
        "وقف التشغيل",
        "اوقف التشغيل",
        "اوقف الفيديو",
        "وقف الفيديو",
        "اوقف الموسيقي",
    )
    private val RESUME = setOf(
        "كمل",
        "كمل التشغيل",
        "استمر",
        "استئناف",
        "استانف",
        "تشغيل",
        "شغل",
    )
    private val NEXT = setOf("التالي", "الي بعده", "اللي بعده", "بعده", "الفيديو التالي", "الاغنيه التاليه")
    private val PREVIOUS = setOf("السابق", "الي قبله", "اللي قبله", "قبله", "الفيديو السابق")

    private val PAUSE_EN = Regex(
        """^(?:please\s+)?(?:pause|pause (?:the )?(?:video|music|song|playback)|stop (?:the )?(?:video|music|song|playback))$""",
    )
    private val RESUME_EN = Regex(
        """^(?:please\s+)?(?:resume|resume playback|continue|continue playing|unpause|play)$""",
    )
    private val NEXT_EN = Regex(
        """^(?:please\s+)?(?:next|next (?:track|song|video)|skip song|skip track)$""",
    )
    private val PREVIOUS_EN = Regex(
        """^(?:please\s+)?(?:previous|previous (?:track|song|video)|last song|go back a song)$""",
    )

    private val PLAY_ON = Regex(
        """^(?:please\s+)?(?:can you\s+)?(?:play|put on|شغل|تشغيل|اسمع)\s+(.+?)\s+(?:on|in|using|via|علي|في)\s+(?:the\s+|ال\s*)?(youtube music|youtube|you tube|spotify|samsung music|samsung|يوتيوب ميوزك|يوتيوب ميوزيك|يوتيوب|يوتوب|اليوتيوب|سبوتيفاي|سبوتفاي|سامسونج ميوزك|سامسونج)$""",
    )
    private val PLAY_QUERY = Regex(
        """^(?:please\s+)?(?:can you\s+)?(?:play|put on|شغل|تشغيل|اسمع)\s+(.+)$""",
    )
    private val OPEN_EN = Regex(
        """^(?:please\s+)?(?:can you\s+)?(?:open|launch|start)\s+(?:the\s+|app\s+)?(.+)$""",
    )
    private val OPEN_AR = Regex(
        """^(?:من فضلك\s+)?(?:افتح|فتح)\s+(?:تطبيق\s+)?(.+)$""",
    )
    // Camera phrases belong to CameraSkill, never to launching com.sec.android.app.camera.
    private val EXCLUDED_OPEN = Regex("""(?:timer|countdown|موقت|تايمر|flashlight|torch|كشاف|فلاش|camera|كاميرا)""")
}
