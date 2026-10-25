package com.jarvis.audio

/**
 * Arabic voice for on-device TTS: a local male voice first (Google ar-xa-x-ard /
 * ar-xa-x-are, or a Samsung male id), then a network male voice, then any local
 * Arabic voice, then any network Arabic voice.
 */
internal object ArabicVoices {
    const val PITCH = 0.95f
    const val RATE = 1.0f

    private val knownMaleIds = listOf("ar-xa-x-ard", "ar-xa-x-are")
    private val knownFemaleIds = listOf("ar-xa-x-arc", "ar-xa-x-arz")

    fun arabicOnly(voices: Iterable<ListedVoice>): List<ListedVoice> = voices.filter { isArabic(it) }

    fun pick(voices: Iterable<ListedVoice>): ListedVoice? {
        val arabic = arabicOnly(voices)
        if (arabic.isEmpty()) return null
        return arabic.minWith(compareBy({ rank(it) }, { it.name }))
    }

    fun isMale(voice: ListedVoice): Boolean {
        val name = voice.name.lowercase()
        if (knownMaleIds.any { name.contains(it) }) return true
        if (knownFemaleIds.any { name.contains(it) }) return false
        return EnglishVoices.isMale(voice)
    }

    fun isArabic(voice: ListedVoice): Boolean {
        val lang = voice.language.lowercase()
        if (lang == "ar" || lang == "ara") return true
        val name = voice.name.lowercase()
        return name.startsWith("ar-") || name.startsWith("ar_")
    }

    private fun rank(voice: ListedVoice): Int {
        val male = isMale(voice)
        val name = voice.name.lowercase()
        // Google's own male ids beat a generic "male" guess.
        val known = knownMaleIds.any { name.contains(it) }
        return when {
            male && !voice.networkRequired -> if (known) 0 else 1
            male -> if (known) 2 else 3
            !voice.networkRequired -> 4
            else -> 5
        }
    }
}
