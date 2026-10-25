package com.jarvis.core.brain

/**
 * Answers that admit they cannot see the live world: the model fell back to
 * memory. A hit lets the chain retry once with live search forced; the caller
 * caps the retry, so this never loops by itself.
 */
object StaleAnswer {
    fun needsLiveRetry(text: String): Boolean {
        val folded = text.lowercase()
        return PHRASES.any { folded.contains(it) }
    }

    private val PHRASES = listOf(
        "real-time",
        "real time",
        "don't have access to",
        "do not have access to",
        "dont have access to",
        "as of my last",
        "knowledge cutoff",
        "cannot browse",
        "can't browse",
        "can not browse",
        "could not browse",
        "لا أستطيع الوصول",
        "لا استطيع الوصول",
        "ليس لدي معلومات حديثة",
        "ليس لدي معلومات محدثه",
        "معلومات محدثة",
        "معلومات محدثه",
    )
}
