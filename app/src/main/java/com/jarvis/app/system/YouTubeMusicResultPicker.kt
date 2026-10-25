package com.jarvis.app.system

import com.jarvis.core.skills.UiNode
import com.jarvis.core.skills.Utterances

/**
 * Picks what to tap on a YouTube Music search page. Pure so tests can feed
 * the exact strings the app publishes on a Galaxy S10. Prefers the top
 * result card's play button, then song/video rows, and never touches the
 * search box, filter chips, the nav bar, or the toolbar.
 */
object YouTubeMusicResultPicker {
    private val playLabels = setOf("play", "تشغيل")
    private val navAndChips = setOf(
        "home",
        "samples",
        "explore",
        "library",
        "search",
        "search music",
        "youtube music",
        "more",
        "menu",
        "back",
        "filter",
        "voice search",
        "songs",
        "videos",
        "albums",
        "artists",
        "community playlists",
        "featured playlists",
        "profiles",
        "podcasts",
        "episodes",
    )
    private val rowHints = listOf(
        " • song",
        "song •",
        " • video",
        "video •",
        "أغنية",
        "فيديو",
    )
    private val queryStopwords = setOf(
        "the",
        "and",
        "a",
        "of",
        "on",
        "by",
        "again",
        "please",
        "play",
        "put",
        "song",
        "music",
        "شغلي",
        "شغل",
        "حط",
        "في",
        "من",
        "على",
        "لو",
        "سمحت",
        "ممكن",
    )
    private const val MIN_TOKEN = 2

    fun bestPlayable(nodes: List<UiNode>): UiNode? {
        val usable = nodes
            .filter { it.clickable && it.width > 0 && it.height > 0 }
            .map { it to label(it) }
            .filter { (_, label) -> label.isNotBlank() && label.lowercase() !in navAndChips }
        val play = usable
            .filter { (_, label) -> isPlayLabel(label) }
            .minByOrNull { (node, _) -> node.top }
            ?.first
        if (play != null) return play
        return usable
            .filter { (_, label) -> isResultRow(label) }
            .minByOrNull { (node, _) -> node.top }
            ?.first
    }

    /**
     * True when the visible rows are (very likely) the new search's results:
     * they carry a significant token from [query] (Arabic folding on both sides),
     * or the rows changed from the snapshot taken before the search was opened.
     * An empty [previous] snapshot proves nothing, so only the token rule applies.
     */
    fun readyFor(nodes: List<UiNode>, query: String, previous: List<UiNode>): Boolean {
        val labels = resultLabels(nodes)
        if (labels.isEmpty()) return false
        if (carriesQueryToken(labels, query)) return true
        if (previous.isEmpty()) return false
        return resultLabels(previous) != labels
    }

    private fun carriesQueryToken(labels: List<String>, query: String): Boolean {
        val token = significantTokens(query)
            .maxByOrNull { it.length }
            ?: return false
        return labels.any { Utterances.normalize(it).contains(token) }
    }

    /** Query tokens worth matching on, normalized (أإآ→ا, ة→ه) and stopword-free. */
    fun significantTokens(query: String): List<String> {
        return Utterances.normalize(query)
            .split(' ')
            .filter { it.length >= MIN_TOKEN && it !in queryStopwords }
    }

    private fun resultLabels(nodes: List<UiNode>): List<String> {
        return nodes
            .filter { it.clickable && it.width > 0 && it.height > 0 }
            .map { label(it) }
            .filter { candidate -> candidate.isNotBlank() && candidate.lowercase() !in navAndChips }
            .take(16)
    }

    private fun label(node: UiNode): String = node.desc.ifBlank { node.text }.trim()

    private fun isPlayLabel(label: String): Boolean {
        val lower = label.lowercase()
        return lower in playLabels || lower.startsWith("play ") || label.startsWith("تشغيل ")
    }

    private fun isResultRow(label: String): Boolean {
        val lower = label.lowercase()
        return rowHints.any { lower.contains(it) }
    }
}
