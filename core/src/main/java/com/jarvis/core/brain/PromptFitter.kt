package com.jarvis.core.brain

/**
 * Fits a system prompt, recent turns, and the current user text into a token budget.
 * Tokens are chars / 4. Oldest turns are dropped first. The current user text is kept
 * ahead of the system text, and the system text is capped at half the budget so a long
 * persona cannot erase the conversation.
 *
 * [memorySystem] is the only assembler for persona, facts, summary, and (for Live) recent turns.
 */
class PromptFitter(
    val tokenBudget: Int = TOKEN_BUDGET,
) {
    init {
        require(tokenBudget > 0)
    }

    fun estimateTokens(text: String): Int = text.length / CHARS_PER_TOKEN

    fun fit(
        system: String,
        turns: List<ConversationTurn>,
        userText: String,
    ): FittedPrompt {
        val charBudget = tokenBudget * CHARS_PER_TOKEN
        val user = takeStart(userText, charBudget)
        var remaining = charBudget - user.length
        val systemCap = minOf(remaining, charBudget / 2)
        val fittedSystem = takeStart(system, systemCap)
        remaining -= fittedSystem.length

        val prior = dropDuplicateUserTail(turns, user)
        val kept = ArrayDeque<ConversationTurn>()
        for (turn in prior.asReversed()) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            if (text.length <= remaining) {
                kept.addFirst(turn.copy(text = text))
                remaining -= text.length
            } else {
                if (kept.isEmpty() && remaining >= CHARS_PER_TOKEN) {
                    kept.addFirst(turn.copy(text = text.takeLast(remaining)))
                }
                break
            }
        }
        val messages = ArrayList<ConversationTurn>(kept.size + 1)
        messages.addAll(kept)
        if (user.isNotEmpty()) messages.add(ConversationTurn(Speaker.USER, user))
        val chars = fittedSystem.length + messages.sumOf { it.text.length }
        return FittedPrompt(
            system = fittedSystem,
            turns = messages,
            estimatedTokens = chars / CHARS_PER_TOKEN,
        )
    }

    private fun dropDuplicateUserTail(turns: List<ConversationTurn>, user: String): List<ConversationTurn> {
        val last = turns.lastOrNull() ?: return turns
        if (user.isNotEmpty() && last.speaker == Speaker.USER && last.text.trim() == user) {
            return turns.dropLast(1)
        }
        return turns
    }

    /**
     * Builds the system text for one brain. [charBudget] is a character cap, not a token cap.
     * Order is base prompt, persona, pinned facts, summary, then the newest turns that still fit.
     * Text brains pass turns separately to [fit]. Live passes them here because the socket only
     * receives the new utterance.
     */
    fun memorySystem(
        baseSystem: String,
        persona: String = "",
        facts: List<MemoryFact> = emptyList(),
        summary: String = "",
        priorTurns: List<ConversationTurn> = emptyList(),
        charBudget: Int = LIVE_CHAR_BUDGET,
    ): String {
        if (charBudget <= 0) return ""
        val blocks = ArrayList<String>()
        var remaining = charBudget

        fun push(block: String, clip: Boolean) {
            val body = block.trim()
            if (body.isEmpty() || remaining <= 0) return
            val sep = if (blocks.isEmpty()) 0 else 2
            val room = remaining - sep
            if (room <= 0) return
            if (body.length <= room) {
                blocks += body
                remaining -= sep + body.length
                return
            }
            if (!clip || room < 16) return
            blocks += body.take(room)
            remaining = 0
        }

        push(baseSystem, clip = true)
        val personaBody = persona.trim().take(PERSONA_CAP)
        if (personaBody.isNotEmpty()) push("About the user:\n$personaBody", clip = true)
        val factBlock = pushFacts(blocks, facts, remaining)
        if (factBlock != null) push(factBlock, clip = false)
        val summaryBody = summary.trim().take(SUMMARY_CAP)
        if (summaryBody.isNotEmpty()) push("Summary:\n$summaryBody", clip = true)
        pushTurns(blocks, priorTurns, remaining)
        return blocks.joinToString("\n\n")
    }

    /**
     * Assembles memory into the system text, then keeps the newest turns inside [charBudget].
     */
    fun fitMemory(
        baseSystem: String,
        persona: String,
        facts: List<MemoryFact>,
        summary: String,
        turns: List<ConversationTurn>,
        userText: String,
        charBudget: Int,
    ): FittedPrompt {
        val tokens = (charBudget / CHARS_PER_TOKEN).coerceAtLeast(1)
        val scoped = if (tokens == tokenBudget) this else PromptFitter(tokens)
        val system = memorySystem(
            baseSystem = baseSystem,
            persona = persona,
            facts = facts,
            summary = summary,
            charBudget = charBudget / 2,
        )
        return scoped.fit(system, turns, userText)
    }

    private fun pushFacts(blocks: MutableList<String>, facts: List<MemoryFact>, remaining: Int): String? {
        val lines = rankedFacts(facts).map { "- ${it.key}: ${it.value}" }
        if (lines.isEmpty()) return null
        val kept = ArrayList<String>()
        for (line in lines) {
            val draft = "Facts:\n" + (kept + line).joinToString("\n")
            val sep = if (blocks.isEmpty()) 0 else 2
            if (draft.length + sep <= remaining) kept += line else break
        }
        if (kept.isEmpty()) return null
        return "Facts:\n" + kept.joinToString("\n")
    }

    private fun pushTurns(blocks: MutableList<String>, priorTurns: List<ConversationTurn>, remaining: Int) {
        val lines = ArrayList<String>()
        for (turn in priorTurns) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            val who = if (turn.speaker == Speaker.USER) "User" else "Jarvis"
            lines += "$who: $text"
        }
        if (lines.isEmpty()) return
        val kept = ArrayDeque<String>()
        var room = remaining
        for (line in lines.asReversed()) {
            val draftLines = ArrayList<String>(kept.size + 1)
            draftLines += line
            draftLines.addAll(kept)
            val draft = "Recent turns:\n" + draftLines.joinToString("\n")
            val sep = if (blocks.isEmpty()) 0 else 2
            if (draft.length + sep <= room) kept.addFirst(line) else break
        }
        if (kept.isEmpty()) return
        val body = "Recent turns:\n" + kept.joinToString("\n")
        val sep = if (blocks.isEmpty()) "" else "\n\n"
        if (sep.length + body.length <= room) blocks += body
    }

    private fun rankedFacts(facts: List<MemoryFact>): List<MemoryFact> {
        return facts
            .map { it.copy(key = it.key.trim(), value = it.value.trim()) }
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            .sortedWith(compareByDescending<MemoryFact> { it.pinned }.thenBy { it.key.lowercase() })
            .take(TOP_FACTS)
    }

    private fun takeStart(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars)
    }

    companion object {
        const val TOKEN_BUDGET = 1_500
        const val CHARS_PER_TOKEN = 4
        const val PERSONA_CAP = 2_000
        const val SUMMARY_CAP = 800
        const val TOP_FACTS = 8
        const val LIVE_CHAR_BUDGET = 3_000
        const val GEMINI_CHAR_BUDGET = TOKEN_BUDGET * CHARS_PER_TOKEN
        const val GEMINI_SYSTEM_CHARS = 3_000
        const val GROQ_SYSTEM_CHARS = 3_000
        const val LOCAL_CHAR_BUDGET = 2_000
    }
}

data class FittedPrompt(
    val system: String,
    val turns: List<ConversationTurn>,
    val estimatedTokens: Int,
)
