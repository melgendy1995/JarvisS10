package com.jarvis.data

import java.util.Locale

/**
 * Phone-only fallback lines are spoken when no cloud brain answers.
 * They are not conversation, and they must not become the next prompt.
 */
object FallbackMemory {
    fun shouldDrop(role: String, text: String, brainId: String): Boolean {
        if (isFallbackText(text)) return true
        return role == TurnRole.ASSISTANT && isOfflineBrain(brainId)
    }

    fun isOfflineBrain(brainId: String): Boolean {
        return brainId.trim().lowercase(Locale.ROOT) == OFFLINE_BRAIN
    }

    fun isFallbackText(text: String): Boolean {
        val body = text.trim().lowercase(Locale.ROOT)
        if (body.isEmpty()) return false
        return MARKERS.any { body.contains(it) }
    }

    private const val OFFLINE_BRAIN = "offline"

    private val MARKERS = listOf(
        "basic phone function",
        "i can tell you the time",
        "i can tell the time",
        "i can still tell the time",
        "no brain yet",
        "only do basic phone",
        "phone-only",
    )
}
