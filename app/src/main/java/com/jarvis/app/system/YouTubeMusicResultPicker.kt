package com.jarvis.app.system

import com.jarvis.core.skills.UiNode

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
