package com.jarvis.core.skills

/** Remembers facts on the phone. The router runs this before any cloud brain. */
class MemorySkill(
    private val store: MemoryStore,
) : Skill {
    override val id: String = "memory"

    override fun match(text: String): SkillCall? {
        val command = MemoryPhrases.parse(text) ?: return null
        return SkillCall(
            skillId = id,
            utterance = text,
            slots = mapOf(
                "action" to command.action,
                "key" to command.key,
                "value" to command.value,
                "query" to command.query,
                "lang" to command.lang,
                "source" to "voice",
            ),
        )
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Remember, recall, or forget a personal fact stored on this phone.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "enum" to listOf("remember", "recall", "forget"),
                ),
                "key" to mapOf("type" to "string"),
                "value" to mapOf("type" to "string"),
                "query" to mapOf("type" to "string"),
            ),
            "required" to listOf("action"),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val arabic = call.slots["lang"] == "ar"
        return when (call.slots["action"]) {
            MemoryPhrases.REMEMBER -> remember(call, arabic)
            MemoryPhrases.RECALL -> recall(call, arabic)
            MemoryPhrases.FORGET -> forget(call, arabic)
            else -> SkillResult(
                text = if (arabic) "ما قدرت." else "I can't do that.",
                success = false,
            )
        }
    }

    private suspend fun remember(call: SkillCall, arabic: Boolean): SkillResult {
        val key = call.slots["key"].orEmpty().trim()
        val value = call.slots["value"].orEmpty().trim()
        if (key.isEmpty() || value.isEmpty()) {
            val text = if (arabic) "ايش أتذكر؟" else "What should I remember?"
            return SkillResult(text = text, success = false)
        }
        val source = call.slots["source"]?.trim().orEmpty().ifEmpty { "voice" }
        store.remember(key, value, source)
        val same = key.equals(value, ignoreCase = true)
        val text = when {
            arabic && same -> "رح أتذكر أن $value."
            arabic -> "رح أتذكر أن $key هو $value."
            same -> "I'll remember that $value."
            else -> "I'll remember that $key is $value."
        }
        return SkillResult(text = text)
    }

    private suspend fun recall(call: SkillCall, arabic: Boolean): SkillResult {
        val facts = store.recall(call.slots["query"].orEmpty()).take(8)
        if (facts.isEmpty()) {
            val text = if (arabic) "ما عندي شيء عن هذا." else "I don't remember anything about that."
            return SkillResult(text = text, success = false)
        }
        val body = facts.joinToString(". ") { fact ->
            if (fact.key.equals(fact.value, ignoreCase = true)) fact.value else "${fact.key}: ${fact.value}"
        }.take(400)
        return SkillResult(text = body)
    }

    private suspend fun forget(call: SkillCall, arabic: Boolean): SkillResult {
        val query = call.slots["query"].orEmpty().trim()
        if (query.isEmpty()) {
            val text = if (arabic) "ايش أنسى؟" else "What should I forget?"
            return SkillResult(text = text, success = false)
        }
        val removed = store.forget(query)
        val text = when {
            removed == 0 && arabic -> "ما كنت متذكر هذا."
            removed == 0 -> "I wasn't remembering that."
            arabic -> "تم النسيان."
            else -> "Forgotten."
        }
        return SkillResult(text = text, success = removed > 0)
    }
}
