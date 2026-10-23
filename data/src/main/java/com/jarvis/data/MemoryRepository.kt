package com.jarvis.data

/**
 * On-device memory. Callers that talk to a cloud brain copy a [MemorySnapshot] into that request.
 * This class does not open a network connection.
 */
class MemoryRepository(
    private val dao: MemoryDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun profile(): Profile = ensureProfile()

    suspend fun savePersona(persona: String) {
        val current = ensureProfile()
        dao.upsertProfile(current.copy(persona = persona.trim().take(MemoryLimits.PERSONA_CHARS)))
    }

    suspend fun snapshot(channel: String): MemorySnapshot {
        val profile = ensureProfile()
        val summary = dao.summary(Summary.ID)
        return MemorySnapshot(
            name = profile.name,
            language = profile.language,
            persona = profile.persona,
            facts = FactRank.top(dao.facts()),
            summary = summary?.text.orEmpty(),
            turns = dao.turns(channel).takeLast(MemoryLimits.KEEP_TURNS),
        )
    }

    suspend fun remember(key: String, value: String, source: String) {
        val cleanKey = key.trim().take(MemoryLimits.KEY_CHARS)
        val cleanValue = value.trim().take(MemoryLimits.VALUE_CHARS)
        if (cleanKey.isEmpty() || cleanValue.isEmpty()) return
        val existing = dao.facts().firstOrNull { it.key.equals(cleanKey, ignoreCase = true) }
        if (existing == null) {
            dao.insertFact(
                Fact(
                    key = cleanKey,
                    value = cleanValue,
                    source = source.trim().ifEmpty { Channels.VOICE },
                    createdAt = now(),
                    pinned = false,
                ),
            )
        } else {
            dao.updateFact(
                existing.copy(
                    value = cleanValue,
                    source = source.trim().ifEmpty { existing.source },
                    createdAt = now(),
                ),
            )
        }
    }

    suspend fun recall(query: String): List<Fact> {
        val matched = FactMatch.matching(dao.facts(), query)
        return FactRank.top(matched)
    }

    suspend fun forget(query: String): Int {
        val needle = query.trim()
        if (needle.isEmpty()) return 0
        val doomed = if (FactMatch.isAll(needle)) dao.facts() else FactMatch.matching(dao.facts(), needle)
        if (doomed.isEmpty()) return 0
        dao.deleteFacts(doomed.map { it.id })
        return doomed.size
    }

    /**
     * Appends one turn for [channel], keeps the newest [MemoryLimits.KEEP_TURNS],
     * and folds anything older into the summary with a local truncation.
     * [PruneOutcome.askCloud] is true once every [MemoryLimits.SUMMARIZE_EVERY] pruned turns.
     */
    suspend fun appendTurn(role: String, text: String, brainId: String, channel: String): PruneOutcome {
        val clean = text.trim()
        if (clean.isEmpty()) return currentOutcome()
        val existing = dao.turns(channel)
        val last = existing.lastOrNull()
        if (last != null && last.role == role && last.text == clean) return currentOutcome()
        dao.insertTurn(
            Turn(
                role = role,
                text = clean,
                brainId = brainId,
                channel = channel,
                timestamp = now(),
            ),
        )
        val all = dao.turns(channel)
        val decision = TurnWindow.prune(all)
        if (decision.dropped.isEmpty()) return currentOutcome()
        dao.deleteTurns(decision.dropped.map { it.id })
        val summary = dao.summary(Summary.ID) ?: Summary()
        val lines = decision.dropped.map { SummaryText.line(it.role, it.text) }
        val local = SummaryText.truncate(summary.text, lines)
        val count = summary.turnsSinceCloud + decision.dropped.size
        val ask = count >= MemoryLimits.SUMMARIZE_EVERY
        val saved = summary.copy(
            text = local,
            updatedAt = now(),
            turnsSinceCloud = if (ask) 0 else count,
        )
        dao.upsertSummary(saved)
        return PruneOutcome(
            dropped = decision.dropped,
            localSummary = local,
            summaryUpdatedAt = saved.updatedAt,
            askCloud = ask,
        )
    }

    /** Writes a cloud rewrite only if the summary has not moved since [expectedUpdatedAt]. */
    suspend fun replaceSummary(expectedUpdatedAt: Long, text: String): Boolean {
        val current = dao.summary(Summary.ID) ?: return false
        if (current.updatedAt != expectedUpdatedAt) return false
        val clean = text.trim().take(MemoryLimits.SUMMARY_CHARS)
        if (clean.isEmpty()) return false
        dao.upsertSummary(current.copy(text = clean, updatedAt = now()))
        return true
    }

    private suspend fun ensureProfile(): Profile {
        val existing = dao.profile(Profile.ID)
        if (existing != null) return existing
        val created = Profile(
            name = Profile.DEFAULT_NAME,
            language = Profile.DEFAULT_LANGUAGE,
            persona = DefaultPersona.TEXT,
        )
        dao.upsertProfile(created)
        return created
    }

    private suspend fun currentOutcome(): PruneOutcome {
        val summary = dao.summary(Summary.ID)
        return PruneOutcome(
            localSummary = summary?.text.orEmpty(),
            summaryUpdatedAt = summary?.updatedAt ?: 0,
        )
    }
}

data class MemorySnapshot(
    val name: String,
    val language: String,
    val persona: String,
    val facts: List<Fact>,
    val summary: String,
    val turns: List<Turn>,
)

data class PruneOutcome(
    val dropped: List<Turn> = emptyList(),
    val localSummary: String = "",
    val summaryUpdatedAt: Long = 0,
    val askCloud: Boolean = false,
)
