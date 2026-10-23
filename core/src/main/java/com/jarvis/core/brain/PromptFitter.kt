package com.jarvis.core.brain

/**
 * Fits a system prompt, recent turns, and the current user text into a token budget.
 * Tokens are chars / 4. Oldest turns are dropped first. The current user text is kept
 * ahead of the system text, and the system text is capped at half the budget so a long
 * persona cannot erase the conversation.
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

    private fun takeStart(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars)
    }

    companion object {
        const val TOKEN_BUDGET = 1_500
        const val CHARS_PER_TOKEN = 4
    }
}

data class FittedPrompt(
    val system: String,
    val turns: List<ConversationTurn>,
    val estimatedTokens: Int,
)
