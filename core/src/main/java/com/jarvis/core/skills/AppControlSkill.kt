package com.jarvis.core.skills

class AppControlSkill(
    private val control: AppControl,
) : Skill {
    override val id: String = "app_control"

    override fun match(text: String): SkillCall? {
        val slots = AppControlPhrases.parse(text) ?: return null
        return SkillCall(skillId = id, utterance = text, slots = slots)
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Open an installed app, play a song or video, or control playback and the music volume. " +
            "Use target youtube when the user names YouTube. Leave target empty to use the default music app.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "enum" to listOf("open", "play", "media", "volume", "skip_ad", "return"),
                ),
                "target" to mapOf(
                    "type" to "string",
                    "description" to "App label, or youtube, youtube_music, spotify, samsung.",
                ),
                "query" to mapOf("type" to "string", "description" to "Song, artist, or video to play."),
                "media" to mapOf(
                    "type" to "string",
                    "enum" to listOf("pause", "resume", "next", "previous"),
                ),
                "volume" to mapOf(
                    "type" to "string",
                    "enum" to listOf("up", "down", "mute", "unmute", "set"),
                ),
                "amount" to mapOf("type" to "string"),
                "unit" to mapOf("type" to "string", "enum" to listOf("percent", "level", "auto")),
            ),
            "required" to listOf("action"),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val slots = if (call.slots["action"].isNullOrBlank()) {
            match(call.utterance)?.slots ?: return SkillResult("I didn't catch that.", success = false)
        } else {
            call.slots
        }
        return control.perform(call.copy(slots = slots))
    }
}
