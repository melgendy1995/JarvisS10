package com.jarvis.core

import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult

/**
 * Phase 1 router. Registered skills run on the phone. Anything else gets a
 * fallback until Gemini, Groq, and the local model exist.
 */
class CommandRouter(
    private val registry: SkillRegistry,
) {
    suspend fun route(text: String): SkillResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return SkillResult(text = "I didn't catch that.", success = false)
        }
        val match = registry.match(trimmed) ?: return NO_BRAIN
        return match.skill.run(match.call)
    }

    companion object {
        val NO_BRAIN = SkillResult(
            text = "No brain yet. I can tell the time, set a timer, or toggle the flashlight.",
            success = false,
        )
    }
}
