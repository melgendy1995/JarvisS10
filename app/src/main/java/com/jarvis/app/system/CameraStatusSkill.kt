package com.jarvis.app.system

import com.jarvis.core.skills.Skill
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillResult
import com.jarvis.core.skills.Utterances

/**
 * Read-only answer for "is anyone watching (the camera)". It reports whether the
 * camera is on and how many viewers are connected, and never starts, stops, or
 * switches anything: the wider brains may call it safely instead of guessing a
 * camera state and turning the stream off.
 */
class CameraStatusSkill(
    private val status: suspend () -> String,
) : Skill {
    override val id: String = "camera_status"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        if (normalized.isEmpty()) return null
        return if (isStatusQuestion(normalized)) {
            SkillCall(skillId = id, utterance = text)
        } else {
            null
        }
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to
            "Read-only report of the camera stream state and how many viewers are " +
            "connected. Never stops the camera.",
        "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override fun groqTool(): Any = mapOf("type" to "function", "function" to geminiDeclaration())

    override suspend fun run(call: SkillCall): SkillResult = SkillResult(text = status())

    companion object {
        private val AUDIENCE_EN = listOf("anyone", "anybody", "someone", "somebody", "who")
        private const val WATCHING_EN = "watching"
        private val CAMERA_EN = listOf("camera", "feed", "stream")
        private val NO_VIEWER_AR = listOf("احد", "حد", "اني حد")
        private val WATCH_AR = listOf("يشوف", "يتفرج", "يطالع")
        private val CAMERA_AR = listOf("كاميرا")

        /** True for "who/anyone is watching (the camera)" questions. Pure so tests can drive it. */
        fun isStatusQuestion(normalized: String): Boolean {
            val arabic = normalized.any { it in '\u0600'..'\u06FF' }
            if (arabic) {
                return WATCH_AR.any { normalized.contains(it) } &&
                    CAMERA_AR.any { normalized.contains(it) } &&
                    NO_VIEWER_AR.any { normalized.contains(it) }
            }
            if (normalized.startsWith("stop") || normalized.startsWith("turn off")) return false
            val watch = normalized.contains(WATCHING_EN)
            if (!watch) return false
            val audience = AUDIENCE_EN.any { normalized.contains(it) }
            return audience || CAMERA_EN.any { normalized.contains(it) }
        }
    }
}
