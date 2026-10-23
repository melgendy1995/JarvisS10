package com.jarvis.provider.groq

import java.time.Duration
import java.time.Instant

data class TokenSpend(
    val atEpochMillis: Long,
    val tokens: Int,
)

data class GroqSnapshot(
    val nextIndex: Int = 0,
    val cooldownsUntilEpochMillis: Map<String, Long> = emptyMap(),
    val spends: Map<String, List<TokenSpend>> = emptyMap(),
)

interface GroqStateStore {
    fun read(): GroqSnapshot
    fun write(snapshot: GroqSnapshot)
}

/**
 * Round-robin across the chat models. A 429 cools only that model.
 * Token counts are a rolling 24h window persisted with the rotation index.
 */
class GroqRotation(
    private val clock: GroqClock,
    private val store: GroqStateStore,
    private val models: List<String> = GroqModels.CHAT,
    private val dailyCap: Int = GroqModels.DAILY_TOKEN_CAP,
    private val window: Duration = GroqModels.USAGE_WINDOW,
) {
    private val lock = Any()

    fun models(): List<String> = models

    fun peekIndex(): Int = synchronized(lock) { normalize(store.read().nextIndex) }

    fun commitIndex(index: Int) = synchronized(lock) {
        val now = clock.now()
        val snapshot = pruned(store.read(), now)
        store.write(snapshot.copy(nextIndex = normalize(index)))
    }

    fun hasAvailableModel(): Boolean = synchronized(lock) {
        val now = clock.now()
        val snapshot = store.read()
        models.any { waitFor(snapshot, it, now).isZero }
    }

    fun isAvailable(model: String): Boolean = synchronized(lock) {
        waitFor(store.read(), model, clock.now()).isZero
    }

    fun tokensUsed(model: String): Int = synchronized(lock) {
        active(store.read().spends[model].orEmpty(), clock.now()).sumOf { it.tokens }
    }

    fun recordTokens(model: String, tokens: Int) = synchronized(lock) {
        if (tokens <= 0) return
        val now = clock.now()
        val snapshot = pruned(store.read(), now)
        val kept = active(snapshot.spends[model].orEmpty(), now) + TokenSpend(now.toEpochMilli(), tokens)
        store.write(snapshot.copy(spends = snapshot.spends + (model to kept)))
    }

    /** Keeps the later deadline when a model is cooled again before the first wait ends. */
    fun coolUntil(model: String, until: Instant) = synchronized(lock) {
        val now = clock.now()
        val snapshot = pruned(store.read(), now)
        val current = snapshot.cooldownsUntilEpochMillis[model]
        val chosen = if (current != null && current > until.toEpochMilli()) current else until.toEpochMilli()
        store.write(snapshot.copy(cooldownsUntilEpochMillis = snapshot.cooldownsUntilEpochMillis + (model to chosen)))
    }

    fun soonestWait(): Duration = synchronized(lock) {
        val now = clock.now()
        val snapshot = store.read()
        if (models.isEmpty()) return Duration.ofHours(24)
        models.minOf { waitFor(snapshot, it, now) }
    }

    private fun waitFor(snapshot: GroqSnapshot, model: String, now: Instant): Duration {
        val coolUntil = snapshot.cooldownsUntilEpochMillis[model]?.let(Instant::ofEpochMilli)
        val cool = if (coolUntil != null && coolUntil.isAfter(now)) {
            Duration.between(now, coolUntil)
        } else {
            Duration.ZERO
        }
        val cap = capWait(snapshot, model, now)
        return if (cool > cap) cool else cap
    }

    private fun capWait(snapshot: GroqSnapshot, model: String, now: Instant): Duration {
        val events = active(snapshot.spends[model].orEmpty(), now).sortedBy { it.atEpochMillis }
        var used = events.sumOf { it.tokens }
        if (used < dailyCap) return Duration.ZERO
        for (event in events) {
            val freesAt = Instant.ofEpochMilli(event.atEpochMillis).plus(window)
            used -= event.tokens
            if (used < dailyCap) {
                val gap = Duration.between(now, freesAt)
                return if (gap.isNegative) Duration.ZERO else gap
            }
        }
        return window
    }

    private fun active(spends: List<TokenSpend>, now: Instant): List<TokenSpend> {
        return spends.filter { spend ->
            if (spend.tokens <= 0) return@filter false
            val age = Duration.between(Instant.ofEpochMilli(spend.atEpochMillis), now)
            !age.isNegative && age < window
        }
    }

    private fun pruned(snapshot: GroqSnapshot, now: Instant): GroqSnapshot {
        val spends = snapshot.spends
            .mapValues { (_, events) -> active(events, now) }
            .filterValues { it.isNotEmpty() }
        val cooldowns = snapshot.cooldownsUntilEpochMillis.filterValues { until ->
            Instant.ofEpochMilli(until).isAfter(now)
        }
        return snapshot.copy(spends = spends, cooldownsUntilEpochMillis = cooldowns)
    }

    private fun normalize(index: Int): Int {
        if (models.isEmpty()) return 0
        val mod = index % models.size
        return if (mod < 0) mod + models.size else mod
    }
}
