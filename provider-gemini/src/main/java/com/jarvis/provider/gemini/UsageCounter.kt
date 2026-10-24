package com.jarvis.provider.gemini

import java.time.LocalDate
import java.time.ZoneId

data class Usage(
    val day: LocalDate,
    val liveTurns: Int = 0,
    val liveSeconds: Long = 0,
    val textRequests: Int = 0,
    val groundedRequests: Int = 0,
)

interface UsageStore {
    fun read(): Usage?
    fun write(usage: Usage)
}

/**
 * Live turns, Live wall-clock seconds, and text requests.
 * The day rolls at midnight [zone] (America/Los_Angeles by default).
 */
class UsageCounter(
    private val time: TimeSource,
    private val store: UsageStore,
    private val zone: ZoneId = ModelConfig.ZONE,
    private val liveTurnCap: Int = ModelConfig.DAILY_LIVE_TURN_CAP,
    private val groundedCap: Int = ModelConfig.DAILY_GROUNDED_CAP,
) {
    private val lock = Any()

    fun snapshot(): Usage = synchronized(lock) { today() }

    fun liveCapReached(): Boolean = synchronized(lock) { today().liveTurns >= liveTurnCap }

    fun recordLiveTurn() = mutate { it.copy(liveTurns = it.liveTurns + 1) }

    fun addLiveSeconds(seconds: Long) {
        if (seconds <= 0L) return
        mutate { it.copy(liveSeconds = it.liveSeconds + seconds) }
    }

    fun recordTextRequest() = mutate { it.copy(textRequests = it.textRequests + 1) }

    fun groundedCapReached(): Boolean = synchronized(lock) {
        today().groundedRequests >= groundedCap
    }

    /** Reserves one grounded call for today. False when [groundedCap] is already used. */
    fun tryAcquireGrounded(): Boolean = synchronized(lock) {
        val current = today()
        if (current.groundedRequests >= groundedCap) return false
        store.write(current.copy(groundedRequests = current.groundedRequests + 1))
        true
    }

    private fun mutate(block: (Usage) -> Usage) {
        synchronized(lock) {
            store.write(block(today()))
        }
    }

    private fun today(): Usage {
        // LocalDate.ofInstant is API 34. This phone is API 31, so derive the day from atZone.
        val day = time.now().atZone(zone).toLocalDate()
        val stored = store.read()
        return if (stored != null && stored.day == day) stored else Usage(day)
    }
}
