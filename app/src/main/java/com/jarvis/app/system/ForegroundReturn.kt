package com.jarvis.app.system

import com.jarvis.core.skills.AppControlPhrases

/**
 * Pure rules for bringing Jarvis back to the front after a skill opened another app.
 * The external app keeps running (music plays on in the background); Jarvis only
 * reorders its own task forward, once per action.
 */
object ForegroundReturn {
    /** Wait this long for confirmed playback before returning anyway. */
    const val PLAY_CONFIRM_TIMEOUT_MS = 6_000L

    /** Two returns closer than this are treated as a loop and the second is dropped. */
    const val MIN_GAP_MS = 2_500L

    /**
     * Actions that should end with Jarvis in front. Opening an app is excluded on
     * purpose: there the user asked to see that app.
     */
    private val RETURN_ACTIONS = setOf(AppControlPhrases.PLAY)

    fun wantsReturn(action: String, launchedPackage: String?, jarvisInFront: Boolean): Boolean =
        action in RETURN_ACTIONS && launchedPackage != null && !jarvisInFront

    /** Loop guard: skip when Jarvis is already resumed or a return just happened. */
    fun allowReturn(jarvisInFront: Boolean, nowMs: Long, lastReturnMs: Long): Boolean =
        !jarvisInFront && (lastReturnMs <= 0L || nowMs - lastReturnMs >= MIN_GAP_MS)

    /**
     * True when the playing item looks like what was asked for. Any query word of 2+
     * characters found in title, artist or album counts. Arabic/English spellings that
     * differ (فيروز vs Fairuz) do not match; the timeout covers those.
     */
    fun metadataMatches(query: String, title: String?, artist: String?, album: String?): Boolean {
        val hay = listOfNotNull(title, artist, album).joinToString(" ").let(::normalize)
        if (hay.isBlank()) return false
        val words = normalize(query).split(' ').filter { it.length >= 2 && it !in STOP }
        if (words.isEmpty()) return false
        return words.any { hay.contains(it) }
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("""[\p{Punct}،؟]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private val STOP = setOf("the", "on", "by", "song", "music", "play", "اغنيه", "اغاني")
}
