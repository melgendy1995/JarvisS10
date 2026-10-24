package com.jarvis.core.skills

enum class CameraOutcome {
    STARTED,
    STOPPED,
    SWITCHED_FRONT,
    SWITCHED_BACK,
    ALREADY_ON,
    ALREADY_OFF,
    NO_PASSWORD,
    NEEDS_PERMISSION,
    THERMAL_STOP,
    NOT_IN_FOREGROUND,
    FAILED,
}

interface CameraController {
    suspend fun turnOn(): CameraOutcome

    suspend fun turnOff(): CameraOutcome

    suspend fun switchCamera(): CameraOutcome
}

/**
 * "camera on", "camera off", and "switch camera", plus the Arabic lines
 * شغل الكاميرا / اطفي الكاميرا / بدل الكاميرا. Registered ahead of app launch
 * so "start the camera" is the stream, not an app named camera.
 */
class CameraSkill(
    private val camera: CameraController,
) : Skill {
    override val id: String = "camera"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        if (normalized.isEmpty()) return null
        val action = action(normalized) ?: return null
        val lang = if (normalized.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        return SkillCall(
            skillId = id,
            utterance = text,
            slots = mapOf("action" to action, "lang" to lang),
        )
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Start, stop, or switch the phone's live camera stream.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "on, off, or switch",
                    "enum" to listOf("on", "off", "switch"),
                ),
            ),
            "required" to listOf("action"),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val action = call.slots["action"] ?: return SkillResult("I didn't catch that.", success = false)
        val arabic = call.slots["lang"] == "ar"
        val outcome = when (action) {
            "on" -> camera.turnOn()
            "off" -> camera.turnOff()
            "switch" -> camera.switchCamera()
            else -> return SkillResult("I didn't catch that.", success = false)
        }
        return speak(action, outcome, arabic)
    }

    private fun speak(action: String, outcome: CameraOutcome, arabic: Boolean): SkillResult {
        return when (outcome) {
            CameraOutcome.STARTED -> line(if (arabic) "الكاميرا شغالة." else "Camera on.")
            CameraOutcome.STOPPED -> line(if (arabic) "الكاميرا طفت." else "Camera off.")
            CameraOutcome.SWITCHED_FRONT -> line(if (arabic) "بدلت للكاميرا الأمامية." else "Switched to the front camera.")
            CameraOutcome.SWITCHED_BACK -> line(if (arabic) "بدلت للكاميرا الخلفية." else "Switched to the back camera.")
            CameraOutcome.ALREADY_ON -> line(if (arabic) "الكاميرا شغالة من قبل." else "The camera is already on.")
            CameraOutcome.ALREADY_OFF -> line(if (arabic) "الكاميرا طافية." else "The camera is already off.")
            CameraOutcome.NO_PASSWORD -> line(
                if (arabic) "حط كلمة سر الكاميرا من الإعدادات أول." else "Set a camera password in settings first.",
                success = false,
            )
            CameraOutcome.NEEDS_PERMISSION -> SkillResult(
                text = if (arabic) "أحتاج إذن الكاميرا عشان أبث." else "I need camera permission to stream.",
                success = false,
                needsPermission = CAMERA_PERMISSION,
            )
            CameraOutcome.THERMAL_STOP -> line(
                if (arabic) "الجوال حر، وقفت الكاميرا." else "The phone is too hot, so I stopped the camera.",
                success = false,
            )
            CameraOutcome.NOT_IN_FOREGROUND -> line(
                if (arabic) "افتح جارفس أول، بعدين شغل الكاميرا." else "Open Jarvis first, then turn the camera on.",
                success = false,
            )
            CameraOutcome.FAILED -> line(
                when {
                    arabic && action == "switch" -> "ما قدرت أبدل الكاميرا."
                    arabic -> "ما قدرت أشغل الكاميرا."
                    action == "switch" -> "I couldn't switch the camera."
                    action == "off" -> "I couldn't stop the camera."
                    else -> "I couldn't start the camera."
                },
                success = false,
            )
        }
    }

    private fun line(text: String, success: Boolean = true) = SkillResult(text = text, success = success)

    private fun action(normalized: String): String? {
        if (normalized in ON_AR) return "on"
        if (normalized in OFF_AR) return "off"
        if (normalized in SWITCH_AR) return "switch"
        if (ON_EN.matches(normalized) || ON_TAIL.matches(normalized) || SIMPLE_ON.matches(normalized)) return "on"
        if (OFF_EN.matches(normalized) || OFF_TAIL.matches(normalized) || SIMPLE_OFF.matches(normalized)) return "off"
        if (SWITCH_EN.matches(normalized)) return "switch"
        return null
    }

    private companion object {
        const val CAMERA_PERMISSION = "android.permission.CAMERA"

        val ON_EN = Regex(
            """^(?:please\s+)?(?:turn on|switch on|start|enable)(?:\s+the)?\s+camera(?:\s+stream)?(?:\s+please)?$""",
        )
        val ON_TAIL = Regex(
            """^(?:please\s+)?turn(?:\s+the)?\s+camera(?:\s+stream)?\s+on(?:\s+please)?$""",
        )
        val SIMPLE_ON = Regex("""^(?:please\s+)?camera on(?:\s+please)?$""")
        val OFF_EN = Regex(
            """^(?:please\s+)?(?:turn off|switch off|stop|disable)(?:\s+the)?\s+camera(?:\s+stream)?(?:\s+please)?$""",
        )
        val OFF_TAIL = Regex(
            """^(?:please\s+)?turn(?:\s+the)?\s+camera(?:\s+stream)?\s+off(?:\s+please)?$""",
        )
        val SIMPLE_OFF = Regex("""^(?:please\s+)?camera off(?:\s+please)?$""")
        val SWITCH_EN = Regex(
            """^(?:please\s+)?(?:switch|flip|toggle)(?:\s+the)?\s+cameras?(?:\s+please)?$""",
        )
        val ON_AR = setOf("شغل الكاميرا")
        val OFF_AR = setOf(
            "اطفي الكاميرا",
            "طفي الكاميرا",
            "اغلق الكاميرا",
            "اقفل الكاميرا",
            "وقف الكاميرا",
        )
        val SWITCH_AR = setOf("بدل الكاميرا", "غير الكاميرا", "قلب الكاميرا")
    }
}
