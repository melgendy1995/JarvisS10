package com.jarvis.data

class FakeMemoryDao : MemoryDao {
    private val profiles = mutableListOf<Profile>()
    private val facts = mutableListOf<Fact>()
    private val summaries = mutableListOf<Summary>()
    private val turns = mutableListOf<Turn>()
    private var factId = 1L
    private var turnId = 1L

    override suspend fun profile(id: Int): Profile? = profiles.firstOrNull { it.id == id }

    override suspend fun upsertProfile(profile: Profile) {
        profiles.removeAll { it.id == profile.id }
        profiles += profile
    }

    override suspend fun facts(): List<Fact> = facts.toList()

    override suspend fun insertFact(fact: Fact): Long {
        val id = if (fact.id == 0L) factId++ else fact.id
        facts += fact.copy(id = id)
        return id
    }

    override suspend fun updateFact(fact: Fact) {
        val index = facts.indexOfFirst { it.id == fact.id }
        if (index >= 0) facts[index] = fact
    }

    override suspend fun deleteFacts(ids: List<Long>) {
        facts.removeAll { it.id in ids }
    }

    override suspend fun summary(id: Int): Summary? = summaries.firstOrNull { it.id == id }

    override suspend fun upsertSummary(summary: Summary) {
        summaries.removeAll { it.id == summary.id }
        summaries += summary
    }

    override suspend fun turns(channel: String): List<Turn> {
        return turns.filter { it.channel == channel }.sortedWith(compareBy({ it.timestamp }, { it.id }))
    }

    override suspend fun insertTurn(turn: Turn): Long {
        val id = if (turn.id == 0L) turnId++ else turn.id
        turns += turn.copy(id = id)
        return id
    }

    override suspend fun deleteTurns(ids: List<Long>) {
        turns.removeAll { it.id in ids }
    }

    override suspend fun countTurns(): Int = turns.size

    override suspend fun deleteAllTurns() {
        turns.clear()
    }
}
