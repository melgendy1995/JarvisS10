package com.jarvis.core.brain

import com.jarvis.core.skills.Utterances

/**
 * Questions whose answer goes stale: news, prices, sports, weather-like facts.
 * On-device skills still win first. This only chooses search once the router misses.
 */
object Freshness {
    fun needsLiveInfo(text: String): Boolean {
        val normalized = Utterances.normalize(text)
        if (normalized.isEmpty()) return false
        if (arabic(normalized)) return true
        return ENGLISH.any { it.containsMatchIn(normalized) }
    }

    private fun arabic(normalized: String): Boolean {
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (tokens.any { it in ARABIC_WORDS }) return true
        return ARABIC_PHRASES.any { normalized.contains(it) }
    }

    private val ENGLISH = listOf(
        "news",
        "latest",
        "today",
        "tonight",
        "current",
        "price",
        "prices",
        "cost",
        "costs",
        "score",
        "scores",
        "standings",
        "gold",
        "stock",
        "stocks",
        "weather",
        "forecast",
        "headline",
        "headlines",
        "breaking",
        "recently",
        "now",
        "who won",
        "right now",
        "this week",
        "this morning",
        "happening",
    ).map { phrase ->
        if (phrase.contains(' ')) {
            Regex(Regex.escape(phrase))
        } else {
            Regex("""\b${Regex.escape(phrase)}\b""")
        }
    }

    /** Folded with [Utterances.normalize]: أإآ→ا, ة→ه. */
    private val ARABIC_WORDS = setOf(
        "اليوم",
        "الان",
        "حاليا",
        "سعر",
        "اسعار",
        "ذهب",
        "الذهب",
        "اخبار",
        "اخر",
        "احدث",
        "عاجل",
        "طقس",
        "الطقس",
        "الجو",
        "الحراره",
        "مباراه",
        "نتيجه",
        "نتائج",
    )

    private val ARABIC_PHRASES = listOf(
        "اخر الاخبار",
        "كم السعر",
        "ما الاخبار",
    )
}
