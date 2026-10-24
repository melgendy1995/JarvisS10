package com.jarvis.core.skills

/**
 * One offline-capable action. The router and, later, Gemini and Groq call [run]
 * with the same [SkillCall]. Declarations stay untyped until those providers exist.
 */
interface Skill {
    val id: String

    /** Deterministic, offline. Null means this skill does not claim [text]. */
    fun match(text: String): SkillCall?

    fun geminiDeclaration(): Any?

    fun groqTool(): Any?

    suspend fun run(call: SkillCall): SkillResult
}

data class SkillCall(
    val skillId: String,
    val utterance: String,
    val slots: Map<String, String> = emptyMap(),
)

data class SkillResult(
    val text: String,
    val success: Boolean = true,
    /** Android permission to request. The skill may still return [text] when this is set. */
    val needsPermission: String? = null,
    /** One-line shelf label. The spoken answer stays in [text]. */
    val shelf: String? = null,
    /**
     * The router should ignore this result and ask a brain.
     * Used when a live skill has no quote and search should answer instead.
     */
    val continueToBrain: Boolean = false,
)
