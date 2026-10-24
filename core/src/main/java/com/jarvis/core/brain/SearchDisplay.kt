package com.jarvis.core.brain

/**
 * What a grounded answer must show on screen.
 * [suggestionsHtml] is Google's Search Suggestions widget. Show that HTML unchanged.
 * [sources] are the grounding chunks. Speech uses [SpokenText] and does not read these.
 */
data class SearchSource(
    val title: String,
    val uri: String,
)

data class SearchDisplay(
    val suggestionsHtml: String? = null,
    val sources: List<SearchSource> = emptyList(),
)
