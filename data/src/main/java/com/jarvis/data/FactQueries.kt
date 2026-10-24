package com.jarvis.data

object FactRank {
    fun top(facts: List<Fact>, limit: Int = MemoryLimits.TOP_FACTS): List<Fact> {
        if (limit <= 0) return emptyList()
        return facts.sortedWith(
            compareByDescending<Fact> { it.pinned }
                .thenByDescending { it.createdAt }
                .thenBy { it.key.lowercase() },
        ).take(limit)
    }
}

object FactMatch {
    fun isAll(query: String): Boolean = fold(query) in ALL

    fun matching(facts: List<Fact>, query: String): List<Fact> {
        if (isAll(query)) return facts
        // Remember stores "the e2e token is pinecone" under the key "e2e token".
        // Recall and forget still say "the e2e token", so drop those articles first.
        val needle = stripArticles(fold(query))
        if (needle.isEmpty()) return emptyList()
        return facts.filter { fact ->
            fold(fact.key).contains(needle) || fold(fact.value).contains(needle)
        }
    }

    private fun fold(raw: String): String = raw.trim().lowercase()

    private fun stripArticles(raw: String): String {
        var text = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in ARTICLES) {
                if (text.startsWith(prefix)) {
                    text = text.removePrefix(prefix).trim()
                    changed = true
                }
            }
        }
        return text
    }

    private val ALL = setOf(
        "",
        "me",
        "myself",
        "everything",
        "all",
        "anything",
        "it all",
        "عني",
        "كل شيء",
        "كلشي",
        "كل شي",
        "اي شيء",
    )

    private val ARTICLES = listOf(
        "that ",
        "this ",
        "my ",
        "the ",
        "ان ",
        "اني ",
        "انني ",
    )
}
