package com.jarvis.core.skills

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val aliases: List<String> = emptyList(),
)

data class AppMatch(
    val app: LaunchableApp?,
    /** True when two different packages share the best score. */
    val ambiguous: Boolean = false,
)

object AppMatcher {
    /** Names the voice might use for a package even when the launcher label differs. */
    fun aliasesFor(packageName: String): List<String> = when (packageName) {
        YOUTUBE -> listOf("youtube", "you tube", "yt", "يوتيوب", "يوتوب", "اليوتيوب")
        YOUTUBE_MUSIC -> listOf(
            "youtube music",
            "yt music",
            "يوتيوب ميوزك",
            "يوتيوب ميوزيك",
            "يوتيوب ميوزق",
        )
        SPOTIFY -> listOf("spotify", "سبوتيفاي", "سبوتفاي", "سبوتي فاي")
        SAMSUNG_MUSIC, SAMSUNG_MUSIC_ALT -> listOf(
            "samsung music",
            "سامسونج ميوزك",
            "موسيقى سامسونج",
            "موسيقي سامسونج",
        )
        else -> emptyList()
    }

    fun best(query: String, apps: List<LaunchableApp>): AppMatch {
        val wanted = Utterances.normalize(query)
        if (wanted.isEmpty() || apps.isEmpty()) return AppMatch(null)
        var bestScore = Int.MAX_VALUE
        val winners = ArrayList<LaunchableApp>()
        for (app in apps) {
            val names = buildList {
                add(app.label)
                addAll(app.aliases)
                addAll(aliasesFor(app.packageName))
            }
            var score = Int.MAX_VALUE
            for (name in names) {
                score = minOf(score, scoreName(wanted, Utterances.normalize(name)))
            }
            if (score == Int.MAX_VALUE) continue
            if (score < bestScore) {
                bestScore = score
                winners.clear()
                winners += app
            } else if (score == bestScore && winners.none { it.packageName == app.packageName }) {
                winners += app
            }
        }
        if (winners.isEmpty()) return AppMatch(null)
        if (winners.size > 1) return AppMatch(app = null, ambiguous = true)
        return AppMatch(winners.first())
    }

    internal fun scoreName(query: String, label: String): Int {
        if (query.isEmpty() || label.isEmpty()) return Int.MAX_VALUE
        if (query == label) return 0
        val shorter = minOf(query.length, label.length)
        val longer = maxOf(query.length, label.length)
        if (shorter >= 3 && (label.contains(query) || query.contains(label))) {
            return 10 + (longer - shorter)
        }
        val distance = levenshtein(query, label)
        val limit = when {
            longer <= 4 -> 1
            longer <= 8 -> 2
            else -> 3
        }
        if (distance <= limit && distance * 3 <= longer) return 50 + distance
        return Int.MAX_VALUE
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val prev = IntArray(right.length + 1) { it }
        val curr = IntArray(right.length + 1)
        for (i in left.indices) {
            curr[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[right.length]
    }

    const val YOUTUBE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val SPOTIFY = "com.spotify.music"
    const val SAMSUNG_MUSIC = "com.sec.android.app.music"
    const val SAMSUNG_MUSIC_ALT = "com.samsung.android.app.music"

    /** Installed players that actually start audio from MEDIA_PLAY_FROM_SEARCH. */
    val DIRECT_PLAY_PACKAGES = listOf(YOUTUBE_MUSIC, SPOTIFY, SAMSUNG_MUSIC, SAMSUNG_MUSIC_ALT)
}
