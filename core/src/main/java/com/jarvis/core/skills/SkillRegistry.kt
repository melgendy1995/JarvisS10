package com.jarvis.core.skills

data class SkillMatch(
    val skill: Skill,
    val call: SkillCall,
)

class SkillRegistry {
    private val skills = mutableListOf<Skill>()

    fun register(skill: Skill) {
        skills.removeAll { it.id == skill.id }
        skills += skill
    }

    fun registered(): List<Skill> = skills.toList()

    /** First registered skill whose [Skill.match] accepts [text]. */
    fun match(text: String): SkillMatch? {
        for (skill in skills) {
            val call = skill.match(text) ?: continue
            return SkillMatch(skill, call)
        }
        return null
    }
}
