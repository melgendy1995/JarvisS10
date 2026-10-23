package com.jarvis.core.skills

enum class TorchStatus {
    ON,
    OFF,
    UNAVAILABLE,
    NEEDS_PERMISSION,
}

fun interface TorchController {
    fun setEnabled(on: Boolean): TorchStatus
}

class FlashlightSkill(
    private val torch: TorchController,
) : Skill {
    override val id: String = "flashlight"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        val enabled = when {
            normalized in ON_AR -> true
            normalized in OFF_AR -> false
            ON_EN.matches(normalized) -> true
            OFF_EN.matches(normalized) -> false
            else -> {
                val switched = SWITCH_EN.matchEntire(normalized) ?: SIMPLE_EN.matchEntire(normalized)
                switched?.let { it.groupValues[1] == "on" }
            }
        } ?: return null
        val lang = if (normalized.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        return SkillCall(
            skillId = id,
            utterance = text,
            slots = mapOf(
                "enabled" to enabled.toString(),
                "lang" to lang,
            ),
        )
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Turn the phone flashlight on or off.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "enabled" to mapOf("type" to "boolean"),
            ),
            "required" to listOf("enabled"),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val on = call.slots["enabled"] == "true"
        val arabic = call.slots["lang"] == "ar"
        return when (torch.setEnabled(on)) {
            TorchStatus.ON -> SkillResult(text = if (arabic) "الكشاف شغال." else "Flashlight on.")
            TorchStatus.OFF -> SkillResult(text = if (arabic) "الكشاف طفى." else "Flashlight off.")
            TorchStatus.UNAVAILABLE -> SkillResult(
                text = if (arabic) "ما قدرت أوصل للكشاف." else "I can't reach the flashlight.",
                success = false,
            )
            TorchStatus.NEEDS_PERMISSION -> SkillResult(
                text = if (arabic) {
                    "أحتاج إذن الكاميرا عشان أشغل الكشاف."
                } else {
                    "I need camera permission to use the flashlight."
                },
                success = false,
                needsPermission = CAMERA_PERMISSION,
            )
        }
    }

    private companion object {
        const val CAMERA_PERMISSION = "android.permission.CAMERA"

        val ON_EN = Regex(
            """^(?:please\s+)?(?:turn on|switch on|enable)(?:\s+the)?\s+(?:flashlight|torch)(?:\s+please)?$""",
        )
        val OFF_EN = Regex(
            """^(?:please\s+)?(?:turn off|switch off|disable)(?:\s+the)?\s+(?:flashlight|torch)(?:\s+please)?$""",
        )
        val SWITCH_EN = Regex(
            """^(?:please\s+)?(?:turn|switch)(?:\s+the)?\s+(?:flashlight|torch)\s+(on|off)(?:\s+please)?$""",
        )
        val SIMPLE_EN = Regex(
            """^(?:please\s+)?(?:flashlight|torch)\s+(on|off)(?:\s+please)?$""",
        )
        val ON_AR = setOf(
            "شغل الكشاف",
            "شغل الفلاش",
            "شغل المصباح",
            "ولع الكشاف",
            "افتح الكشاف",
            "نور الكشاف",
        )
        val OFF_AR = setOf(
            "اطفي الكشاف",
            "اطفي الفلاش",
            "اطفي المصباح",
            "اغلق الكشاف",
            "اقفل الكشاف",
            "اغلق الفلاش",
            "طفي الكشاف",
        )
    }
}
