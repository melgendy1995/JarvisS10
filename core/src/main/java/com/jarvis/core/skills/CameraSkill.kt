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

/** Which camera to stream. The front lens is the default everywhere. */
enum class CameraLens {
    FRONT,
    BACK;

    companion object {
        val DEFAULT = FRONT

        fun fromName(name: String?): CameraLens? = when (name?.lowercase()) {
            "front" -> FRONT
            "back" -> BACK
            else -> null
        }
    }
}

interface CameraController {
    suspend fun turnOn(lens: CameraLens = CameraLens.DEFAULT): CameraOutcome

    suspend fun turnOff(): CameraOutcome

    suspend fun switchCamera(): CameraOutcome
}

/**
 * Start, stop, and switch the live camera stream plus the Arabic lines
 * شغل الكاميرا / اطفي الكاميرا / بدل الكاميرا. "Open camera", "show me the
 * camera", and افتح الكاميرا also start the stream with an optional lens, so
 * they are claimed here instead of reaching the app launcher. Registered ahead
 * of app launch so these phrases never open com.sec.android.app.camera.
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
            slots = buildMap {
                put("action", action)
                put("lang", lang)
                if (action == "on") put("lens", lensOf(normalized).name.lowercase())
            },
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
                "lens" to mapOf(
                    "type" to "string",
                    "description" to "Lens to start with when the action is on. Front is the default.",
                    "enum" to listOf("front", "back"),
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
        val lens = CameraLens.fromName(call.slots["lens"]) ?: CameraLens.DEFAULT
        val outcome = when (action) {
            "on" -> camera.turnOn(lens)
            "off" -> camera.turnOff()
            "switch" -> camera.switchCamera()
            else -> return SkillResult("I didn't catch that.", success = false)
        }
        return speak(action, lens, outcome, arabic)
    }

    private fun speak(action: String, lens: CameraLens, outcome: CameraOutcome, arabic: Boolean): SkillResult {
        return when (outcome) {
            CameraOutcome.STARTED -> line(
                if (arabic) "الكاميرا شغالة." else when (lens) {
                    CameraLens.FRONT -> "Front camera on."
                    CameraLens.BACK -> "Back camera on."
                },
            )
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
        if (normalized in OFF_AR) return "off"
        if (normalized in SWITCH_AR) return "switch"
        if (OFF_EN.matches(normalized) || OFF_TAIL.matches(normalized) || SIMPLE_OFF.matches(normalized)) return "off"
        if (SWITCH_EN.matches(normalized)) return "switch"
        if (normalized in ON_AR) return "on"
        if (START_EN.matches(normalized) || START_TAIL.matches(normalized) ||
            SIMPLE_ON.matches(normalized) || START_AR.matches(normalized)
        ) {
            return "on"
        }
        return null
    }

    /** Lens word anywhere in a recognized start phrase, or the front default when none was said. */
    private fun lensOf(normalized: String): CameraLens {
        return when {
            FRONT_WORDS.any { normalized.contains(it) } -> CameraLens.FRONT
            BACK_WORDS.any { normalized.contains(it) } -> CameraLens.BACK
            else -> CameraLens.DEFAULT
        }
    }

    private companion object {
        const val CAMERA_PERMISSION = "android.permission.CAMERA"

        const val LENS_EN = "(?:front|rear|back)"
        const val LENS_AR = "(?:(?:ال)?اماميه|(?:ال)?خلفيه)"

        val FRONT_WORDS = setOf("front", "الاماميه", "اماميه")
        val BACK_WORDS = setOf("rear", "back", "الخلفيه", "خلفيه")

        /**
         * Start verbs that the app launcher also wants: open, show, start, turn on.
         * An optional lens word may sit between the verb and "camera". Patterns run
         * on [Utterances.normalize] (أإآ→ا, ة→ه, ى→ي).
         */
        val START_EN = Regex(
            """^(?:please\s+)?(?:turn on|switch on|start|open|show|enable)(?:(?:\s+me)?\s+(?:the|my))?\s*($LENS_EN)?\s*camera(?:\s+stream)?(?:\s+please)?$""",
        )
        val START_TAIL = Regex(
            """^(?:please\s+)?turn(?:\s+the)?\s*($LENS_EN)?\s*camera(?:\s+stream)?\s+on(?:\s+please)?$""",
        )
        val SIMPLE_ON = Regex("""^(?:please\s+)?($LENS_EN)?\s*camera\s+on(?:\s+please)?$""")
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

        /** شغل/افتح + كاميرا with an optional lens word (اماميه/خلفيه after normalize). */
        val START_AR = Regex(
            """^(?:شغل|اشغل|افتح|اعرض|وريني|ورني)\s+(?:كاميرا الجوال|الكاميرا|كاميرا)(?:\s+($LENS_AR))?$""",
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
