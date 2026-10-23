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
        val needle = fold(query)
        if (needle.isEmpty()) return facts
        return facts.filter { fact ->
            fold(fact.key).contains(needle) || fold(fact.value).contains(needle)
        }
    }

    private fun fold(raw: String): String = raw.trim().lowercase()

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
}
