package com.jarvis.audio

/**
 * Offline English voice for on-device TTS. British first, then American.
 * A voice counts as male when its name or features say male (and not female),
 * or when it is a known Google male id.
 */
internal data class ListedVoice(
    val name: String,
    val language: String,
    val country: String,
    val networkRequired: Boolean,
    val features: Set<String> = emptySet(),
)

internal object EnglishVoices {
    const val PITCH = 0.9f
    const val RATE = 1.0f

    private val knownMaleIds = listOf(
        "en-gb-x-rjs",
        "en-gb-x-gbd",
        "en-us-x-iol",
        "en-us-x-iom",
        "en-us-x-tpd",
    )

    fun pick(voices: Iterable<ListedVoice>): ListedVoice? {
        val all = voices.toList()
        val british = all.filter { matches(it, "GB") && !it.networkRequired }
        if (british.isNotEmpty()) return preferMale(british)
        val american = all.filter { matches(it, "US") && !it.networkRequired }
        if (american.isNotEmpty()) return preferMale(american)
        return null
    }

    fun enGbNames(voices: Iterable<ListedVoice>): List<String> {
        return voices.filter { matches(it, "GB") }.map { it.name }.sorted()
    }

    fun isBritish(voice: ListedVoice): Boolean = matches(voice, "GB")

    fun isMale(voice: ListedVoice): Boolean {
        val name = voice.name.lowercase()
        if (knownMaleIds.any { name.contains(it) }) return true
        // Samsung ids: SMTf## female, SMTm## / SMTd## male.
        if (name.contains("smtf")) return false
        if (name.contains("smtm") || name.contains("smtd")) return true
        val haystack = buildString {
            append(name)
            voice.features.forEach { feature ->
                append(' ')
                append(feature.lowercase())
            }
        }
        if (haystack.contains("female")) return false
        return haystack.contains("male")
    }

    private fun preferMale(offline: List<ListedVoice>): ListedVoice {
        return offline.minWith(compareBy({ rank(it) }, { it.name }))
    }

    private fun rank(voice: ListedVoice): Int {
        val name = voice.name.lowercase()
        if (name.contains("en-gb-x-rjs")) return 0
        if (name.contains("en-gb-x-gbd")) return 1
        if (isMale(voice)) return 2
        return 3
    }

    private fun matches(voice: ListedVoice, country: String): Boolean {
        val lang = voice.language.lowercase()
        val region = voice.country.lowercase()
        val english = lang == "en" || lang == "eng"
        val british = country.equals("GB", ignoreCase = true)
        if (english) {
            val regions = if (british) setOf("gb", "gbr", "uk") else setOf("us", "usa")
            if (region in regions) return true
        }
        val name = voice.name.lowercase()
        val token = if (british) "en-gb" else "en-us"
        return name.contains(token) || name.contains(token.replace('-', '_'))
    }
}
