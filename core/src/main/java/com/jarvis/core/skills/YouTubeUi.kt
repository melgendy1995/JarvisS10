package com.jarvis.core.skills

/**
 * One accessibility node, already copied off the binder object so tests can
 * use the strings YouTube exposes on a Galaxy S10.
 */
data class UiNode(
    val text: String,
    val desc: String,
    val viewId: String,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * Picks the first real video on a YouTube search page and the in-player controls.
 * Ads, Shorts shelves, and channel rows are skipped. View ids win when YouTube
 * still publishes them; content descriptions cover the search rows, which often
 * have an empty id.
 */
object YouTubeUi {
    fun firstPlayable(nodes: List<UiNode>, screenWidth: Int, screenHeight: Int): UiNode? {
        return firstVideo(nodes, screenWidth, screenHeight)
            ?: firstMix(nodes, screenWidth, screenHeight)
    }

    /**
     * Topmost real video. Ads, Shorts, channels, and mixes are not included.
     * A clickable parent that only inherited a child's duration loses to the inner row.
     */
    fun firstVideo(nodes: List<UiNode>, screenWidth: Int, screenHeight: Int): UiNode? {
        val videos = ranked(nodes, screenWidth, screenHeight)
            .filter { it.second == Kind.VIDEO }
            .map { it.first }
        val leaves = videos.filter { outer -> videos.none { inner -> strictlyInside(outer, inner) } }
        return leaves.minByOrNull { it.top }
    }

    fun firstMix(nodes: List<UiNode>, screenWidth: Int, screenHeight: Int): UiNode? {
        return ranked(nodes, screenWidth, screenHeight)
            .filter { it.second == Kind.MIX }
            .minByOrNull { it.first.top }
            ?.first
    }

    /** Watch page, including the landscape player that sits beside the queue. */
    fun isFullPlayer(nodes: List<UiNode>, screenWidth: Int): Boolean {
        val player = playerBounds(nodes) ?: return false
        val width = screenWidth.coerceAtLeast(1)
        val tall = player.height > 400
        val portrait = player.width >= (width * 0.7f).toInt() && player.top < 500 && tall
        val landscape = player.width >= (width * 0.35f).toInt() && player.top < 800 && tall
        return portrait || landscape
    }

    /** Search results have drawn. The search field alone does not count. */
    fun hasLoadedRows(nodes: List<UiNode>): Boolean {
        return nodes.any { node ->
            if (!node.clickable || node.top < 260 || node.height < 200 || node.width < 400) return@any false
            val blob = fold(normalize("${node.desc} ${node.text}"))
            blob.isNotBlank() && !isChrome(blob)
        }
    }

    /** Playlist page button. Tapping the row only opens the playlist. */
    fun playAll(nodes: List<UiNode>): UiNode? {
        return nodes.firstOrNull { node ->
            if (!node.clickable) return@firstOrNull false
            val blob = fold(normalize("${node.text} ${node.desc}"))
            blob == "play all" || blob.startsWith("play all ") || blob == "تشغيل الكل" || blob.startsWith("تشغيل الكل ")
        }
    }

    fun skipButton(nodes: List<UiNode>): UiNode? {
        return nodes.firstOrNull { node ->
            node.clickable &&
                !node.viewId.contains("miniplayer", ignoreCase = true) &&
                isSkipAd(node.text, node.desc, node.viewId)
        }
    }

    /**
     * Player button to tap for [action] (`pause`, `play`, `next`, `previous`).
     * A pause request returns null when the button already says play.
     */
    fun control(nodes: List<UiNode>, action: String): UiNode? {
        val buttons = nodes.filter { it.clickable }
        return when (action) {
            "pause" -> buttons.firstOrNull { controlKind(it) == "pause" }
            "play" -> buttons.firstOrNull { controlKind(it) == "play" }
            "next" -> buttons.firstOrNull { controlKind(it) == "next" }
            "previous" -> buttons.firstOrNull { controlKind(it) == "previous" }
            else -> null
        }
    }

    /** True when the named control is already in the requested state. */
    fun controlAlready(nodes: List<UiNode>, action: String): Boolean {
        val kinds = nodes.filter { it.clickable }.map { controlKind(it) }
        return when (action) {
            "pause" -> "play" in kinds && "pause" !in kinds
            "play" -> "pause" in kinds && "play" !in kinds
            else -> false
        }
    }

    fun playerBounds(nodes: List<UiNode>): UiNode? {
        return nodes.firstOrNull {
            it.viewId.endsWith("/watch_player") || it.desc.equals("Video player", ignoreCase = true)
        }
    }

    fun isResults(nodes: List<UiNode>): Boolean {
        return nodes.any { it.viewId.endsWith("/search_query") || it.viewId.endsWith(":id/search_query") }
    }

    fun isSkipAd(text: String, desc: String, viewId: String): Boolean {
        val blob = fold(normalize("$text $desc".trim()))
        if (COUNTDOWN.containsMatchIn(blob)) return false
        val id = viewId.lowercase()
        val idHit = id.contains("skip_ad") || id.contains("ad_skip") || id.contains("modern_skip")
        if (idHit) {
            if (blob.isEmpty()) return true
            return blob.contains("skip") || blob.contains("تخطي") || SKIP.matches(blob)
        }
        if (blob.isEmpty() || blob.length > 40) return false
        return SKIP.matches(blob)
    }

    private enum class Kind { VIDEO, MIX, SKIP }

    private fun ranked(
        nodes: List<UiNode>,
        screenWidth: Int,
        screenHeight: Int,
    ): List<Pair<UiNode, Kind>> {
        val width = screenWidth.coerceAtLeast(1)
        val found = ArrayList<Pair<UiNode, Kind>>()
        for (node in nodes) {
            if (!node.clickable) continue
            if (node.top > screenHeight || node.bottom < 80) continue
            val kind = kind(node, width)
            if (kind != Kind.SKIP) found += node to kind
        }
        return found
    }

    private fun kind(node: UiNode, screenWidth: Int): Kind {
        val blob = fold(normalize("${node.desc} ${node.text}"))
        if (blob.isBlank()) return Kind.SKIP
        if (isChrome(blob)) return Kind.SKIP
        if (!resultSized(node, screenWidth)) return Kind.SKIP
        if (isAd(blob)) return Kind.SKIP
        if (isShorts(blob, node.viewId)) return Kind.SKIP
        if (isChannel(blob)) return Kind.SKIP
        if (hasDuration(blob)) return Kind.VIDEO
        if (isMix(blob)) return Kind.MIX
        return Kind.SKIP
    }

    private fun controlKind(node: UiNode): String {
        val id = node.viewId.lowercase()
        val desc = node.desc.lowercase()
        val text = node.text.lowercase()
        if (id.contains("play_pause") || desc == "play video" || desc == "pause video" || desc == "replay video") {
            return when {
                desc.startsWith("pause") || text.startsWith("pause") -> "pause"
                desc.startsWith("play") || text.startsWith("play") -> "play"
                else -> "play"
            }
        }
        if (id.contains("next_button") || desc == "next video" || desc == "next") return "next"
        if (id.contains("previous_button") || desc == "previous video" || desc == "previous") return "previous"
        if (desc == "ايقاف مؤقت" || desc == "إيقاف مؤقت" || text == "ايقاف مؤقت") return "pause"
        if (desc == "تشغيل" || text == "تشغيل") return "play"
        if (desc == "الفيديو التالي" || desc == "التالي") return "next"
        if (desc == "الفيديو السابق" || desc == "السابق") return "previous"
        return ""
    }

    /**
     * Portrait results are one full-width row. Landscape shelves are a strip of cards,
     * about 560 by 293 on this phone, so a 62% width rule never sees them.
     */
    private fun resultSized(node: UiNode, screenWidth: Int): Boolean {
        val fullRow = node.width >= (screenWidth * 0.62f).toInt() && node.height >= 280
        val shelfCard = node.width in 480..(screenWidth / 2) && node.height >= 240
        return fullRow || shelfCard
    }

    private fun isAd(blob: String): Boolean {
        val head = blob.take(48)
        return head.startsWith("sponsored") ||
            head.startsWith("advertisement") ||
            head.startsWith("ad ") ||
            head.startsWith("ad-") ||
            head.startsWith("اعلان") ||
            head.startsWith("ممول") ||
            head.startsWith("برعايه")
    }

    private fun isShorts(blob: String, viewId: String): Boolean {
        val id = viewId.lowercase()
        if (id.contains("shorts") || id.contains("reel")) return true
        return blob == "shorts" || blob.startsWith("shorts ") || blob.contains("#shorts") || blob.contains("شورت")
    }

    private fun isChannel(blob: String): Boolean {
        if (hasDuration(blob)) return false
        return blob.startsWith("go to channel") ||
            blob.startsWith("subscribe") ||
            blob.startsWith("view channel") ||
            blob == "youtube music" ||
            blob.contains("subscriber")
    }

    private fun isMix(blob: String): Boolean {
        return blob.startsWith("mix ") ||
            blob.startsWith("mix -") ||
            blob.startsWith("playlist ") ||
            blob.startsWith("playlist -") ||
            blob.contains(" curated by youtube")
    }

    private fun hasDuration(blob: String): Boolean = DURATION.containsMatchIn(blob)

    private fun strictlyInside(outer: UiNode, inner: UiNode): Boolean {
        if (inner.left < outer.left || inner.right > outer.right) return false
        if (inner.top < outer.top || inner.bottom > outer.bottom) return false
        return inner.width < outer.width || inner.height < outer.height
    }

    private fun isChrome(blob: String): Boolean {
        return blob in CHROME || blob.startsWith("search youtube") || blob.startsWith("navigate up")
    }

    private fun normalize(raw: String): String {
        val folded = buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    in '٠'..'٩' -> append('0' + (ch - '٠'))
                    in '۰'..'۹' -> append('0' + (ch - '۰'))
                    else -> append(ch)
                }
            }
        }
        return folded
            .replace('\u00a0', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    /** Hamza and teh marbuta, without stripping ':' out of durations. */
    private fun fold(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val DURATION = Regex(
        """\d{1,2}:\d{2}(?::\d{2})?|\d+\s+hours?|\d+\s+minutes?|\d+\s+seconds?|\d+\s+ساع|\d+\s+دقيق""",
    )

    private val SKIP = Regex(
        """^(?:skip(?:\s+ads?)?|تخطي(?:\s+الاعلان(?:ات)?)?)$""",
    )
    private val COUNTDOWN = Regex("""skip(?:\s+ads?)?\s+in\b""")

    private val CHROME = setOf(
        "home",
        "shorts",
        "create",
        "subscriptions",
        "you",
        "clear",
        "voice search",
        "more options",
        "action menu",
        "search",
        "notifications",
        "cast",
        "cast. disconnected",
        "minimize",
        "enter fullscreen",
        "exit fullscreen",
        "captions",
        "drag handle",
        "explore menu",
        "search with your voice",
        "autoplay is on",
        "autoplay is off",
        "visit site",
        "visit advertiser",
        "learn more",
        "install",
        "sign in",
        "play video",
        "pause video",
        "next video",
        "previous video",
    )
}
