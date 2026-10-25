package com.jarvis.core.brain

import com.jarvis.core.skills.Utterances

/**
 * Questions whose answer goes stale: news, prices, sports, weather-like facts.
 * On-device skills still win first. This only chooses search once the router misses.
 * English patterns run on the folded text, so apostrophes are already gone.
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

    private val YEAR = Regex("""\b20(?:2[4-9]|3[0-9])\b""")

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
        "rate",
        "rates",
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
        // Live-value additions, matched on the folded text (apostrophes already stripped).
        "worth",
        "game",
        "result",
        "results",
        "winner",
        "won",
        "fixture",
        "election",
        "event",
        "events",
        "release",
        "released",
        "launch",
        "launched",
        "update",
        "trending",
        "crypto",
        "bitcoin",
        "dollar",
        "dollars",
        "riyal",
        "euro",
        "temperature",
        "yesterday",
        "tomorrow",
        "stock market",
        "exchange rate",
        "how much is",
        "how much was",
        "who is",
        "whos",
        "whats new",
        "what happened",
        "what is happening",
        "whats going on",
        "whats happening",
        "did anyone win",
    ).map { phrase ->
        if (phrase.contains(' ')) {
            Regex(Regex.escape(phrase))
        } else {
            Regex("""\b${Regex.escape(phrase)}\b""")
        }
    } + YEAR

    /** Folded with [Utterances.normalize]: أإآ→ا, ة→ه, ى→ي, ؤ→و, ئ→ي. */
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
        // Live-value additions, folded the same way.
        "مباراة",
        "دولار",
        "الدولار",
        "ريال",
        "الريال",
        "يورو",
        "اليورو",
        "بيتكوين",
        "فوركس",
        "الدوري",
        "هدف",
        "فاز",
        "فائز",
        "الفائز",
        "امس",
        "بكره",
        "غدا",
        "الغد",
        "حدث",
        "احداث",
        "الاحداث",
        "انتخابات",
        "الانتخابات",
        "اصدار",
        "الاصدار",
    )

    private val ARABIC_PHRASES = listOf(
        "اخر الاخبار",
        "كم السعر",
        "ما الاخبار",
        // Live-value additions.
        "كم سعر",
        "سعر صرف",
        "من هو",
        "من هي",
        "مين هو",
        "مين هي",
        "ماذا حدث",
        "وش صار",
        "ايش صار",
        "شو صار",
        "وش حدث",
        "ما الذي يحدث",
        "وش الجديد",
        "ايش الجديد",
        "من فاز",
        "من الفائز",
        "هذا الاسبوع",
        "هذا الشهر",
        "هذه السنه",
        "هذه السنة",
    )
}
