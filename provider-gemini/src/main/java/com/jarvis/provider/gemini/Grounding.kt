@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.jarvis.provider.gemini

import com.google.firebase.ai.type.GenerateContentResponse
import com.jarvis.core.brain.SearchDisplay
import com.jarvis.core.brain.SearchSource
import com.jarvis.core.brain.SpokenText
import java.net.URI

internal data class GroundedBits(
    val text: String,
    val search: SearchDisplay?,
    val domains: List<String>,
    val queries: Int,
    val suggestions: Boolean,
)

internal fun GenerateContentResponse.toGroundedSpeech(): GroundedBits {
    val spoken = SpokenText.forSpeech(text.orEmpty())
    val meta = candidates.firstOrNull()?.groundingMetadata
    val domains = LinkedHashSet<String>()
    val sources = ArrayList<SearchSource>()
    val seenUris = HashSet<String>()
    for (chunk in meta?.groundingChunks.orEmpty()) {
        val web = chunk.web ?: continue
        val domain = sourceDomain(web.title, web.domain, web.uri)
        if (!domain.isNullOrBlank()) domains += domain
        val uri = web.uri?.trim().orEmpty()
        if (uri.isEmpty() || !seenUris.add(uri)) continue
        val title = web.title?.trim().orEmpty().ifBlank { domain.orEmpty() }.ifBlank { "source" }
        sources += SearchSource(title = title, uri = uri)
    }
    val html = meta?.searchEntryPoint?.renderedContent?.trim()?.takeIf { it.isNotEmpty() }
    val queries = meta?.webSearchQueries.orEmpty().count { it.isNotBlank() }
    val search = if (html == null && sources.isEmpty()) {
        null
    } else {
        SearchDisplay(suggestionsHtml = html, sources = sources)
    }
    return GroundedBits(
        text = spoken,
        search = search,
        domains = domains.toList(),
        queries = queries,
        suggestions = html != null,
    )
}

/** Prefer the publisher name. Redirect hosts such as vertexaisearch are not the source. */
internal fun sourceDomain(title: String?, domain: String?, uri: String?): String? {
    val titled = title?.trim().orEmpty()
    if (looksLikeHost(titled)) return titled.lowercase()
    val named = domain?.trim().orEmpty()
    if (looksLikeHost(named) && !named.contains("google", ignoreCase = true)) return named.lowercase()
    val host = runCatching { URI(uri).host }.getOrNull()?.removePrefix("www.")?.lowercase()
    if (host.isNullOrBlank()) return titled.takeIf { it.isNotBlank() }?.lowercase()
    if (host.contains("vertexaisearch") || host.endsWith("google.com")) {
        return titled.takeIf { it.isNotBlank() }?.lowercase()
    }
    return host
}

private fun looksLikeHost(value: String): Boolean {
    return value.contains('.') && !value.contains(' ') && !value.contains('/') && !value.contains(':')
}
