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
    /** Android permission name to request before the skill can run, when set. */
    val needsPermission: String? = null,
)
