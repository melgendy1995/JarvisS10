package com.jarvis.core.skills

data class MemoryCommand(
    val action: String,
    val key: String = "",
    val value: String = "",
    val query: String = "",
    val lang: String = "en",
)

object MemoryPhrases {
    const val REMEMBER = "remember"
    const val RECALL = "recall"
    const val FORGET = "forget"

    fun parse(raw: String): MemoryCommand? {
        val normalized = Utterances.normalize(raw)
        if (normalized.isEmpty()) return null
        val lang = if (normalized.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        remember(normalized)?.let { statement ->
            val (key, value) = split(statement)
            return MemoryCommand(REMEMBER, key = key, value = value, lang = lang)
        }
        recall(normalized)?.let { query ->
            return MemoryCommand(RECALL, query = query.trim(), lang = lang)
        }
        forget(normalized)?.let { query ->
            return MemoryCommand(FORGET, query = query.trim(), lang = lang)
        }
        return null
    }

    fun split(statement: String): Pair<String, String> {
        val text = statement.trim().trim('.').trim()
        EN_IS.matchEntire(text)?.let { match ->
            val key = stripKey(match.groupValues[1])
            val value = match.groupValues[2].trim()
            if (key.isNotEmpty() && value.isNotEmpty()) return key to value
        }
        AR_IS.matchEntire(text)?.let { match ->
            val key = stripKey(match.groupValues[1])
            val value = match.groupValues[2].trim()
            if (key.isNotEmpty() && value.isNotEmpty()) return key to value
        }
        val key = stripKey(text).take(80).ifEmpty { text.take(80) }
        return key to text
    }

    private fun remember(text: String): String? {
        REMEMBER_EN.matchEntire(text)?.let { return it.groupValues[1].trim() }
        REMEMBER_AR.matchEntire(text)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun recall(text: String): String? {
        RECALL_EN.matchEntire(text)?.let { return it.groupValues[1] }
        RECALL_AR.matchEntire(text)?.let { return it.groupValues[1] }
        return null
    }

    private fun forget(text: String): String? {
        FORGET_EN.matchEntire(text)?.let { return it.groupValues[1].trim() }
        FORGET_AR.matchEntire(text)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun stripKey(raw: String): String {
        var key = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in KEY_PREFIXES) {
                if (key.startsWith(prefix)) {
                    key = key.removePrefix(prefix).trim()
                    changed = true
                }
            }
        }
        return key
    }

    private val REMEMBER_EN = Regex("""^(?:please )?remember(?: that| this)? (.+)$""")
    private val RECALL_EN = Regex("""^(?:please )?what do you remember(?: about| of)?(?: (.+))?$""")
    private val FORGET_EN = Regex("""^(?:please )?forget(?: that| about| this)? (.+)$""")
    private val REMEMBER_AR = Regex("""^(?:من فضلك )?(?:تذكر|احفظ) ان(?:ني|ي|و)? (.+)$""")
    private val RECALL_AR = Regex("""^(?:ماذا تتذكر|شو تتذكر|ايش تتذكر)(?: عن| حول)?(?: (.+))?$""")
    private val FORGET_AR = Regex("""^(?:انس|انسي|امسح)(?: ان| عن)? (.+)$""")
    private val EN_IS = Regex("""^(.*?) (?:is|are) (.+)$""")
    private val AR_IS = Regex("""^(.*?) (?:هو|هي) (.+)$""")
    private val KEY_PREFIXES = listOf("that ", "this ", "my ", "the ", "ان ", "اني ", "انني ")
}
