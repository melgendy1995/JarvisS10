package com.jarvis.provider.groq

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqRotationTest {
    private val start = Instant.parse("2026-10-23T18:00:00Z")
    private val clock = ManualClock(start)

    @Test
    fun cooldownHonorsRetryAfterForThatModelOnly() {
        val rotation = rotation()
        val first = GroqModels.CHAT[0]
        val second = GroqModels.CHAT[1]
        rotation.coolUntil(first, start.plusSeconds(90))
        assertFalse(rotation.isAvailable(first))
        assertTrue(rotation.isAvailable(second))
        clock.instant = start.plusSeconds(89)
        assertFalse(rotation.isAvailable(first))
        clock.instant = start.plusSeconds(90)
        assertTrue(rotation.isAvailable(first))
        assertEquals(0, rotation.tokensUsed(first))
    }

    @Test
    fun tokenCapStopsAModelUntilTheRollingDayDropsTheSpend() {
        val store = MemoryGroqStore()
        val rotation = rotation(store)
        val model = GroqModels.CHAT[0]
        rotation.recordTokens(model, 179_999)
        assertTrue(rotation.isAvailable(model))
        rotation.recordTokens(model, 1)
        assertEquals(180_000, rotation.tokensUsed(model))
        assertFalse(rotation.isAvailable(model))
        assertTrue(rotation.isAvailable(GroqModels.CHAT[1]))
        GroqModels.CHAT.drop(1).forEach { rotation.recordTokens(it, GroqModels.DAILY_TOKEN_CAP) }
        assertEquals(Duration.ofHours(24), rotation.soonestWait())
        clock.instant = start.plus(Duration.ofHours(24))
        assertEquals(0, rotation.tokensUsed(model))
        assertTrue(rotation.isAvailable(model))
        val restarted = GroqRotation(clock, store)
        assertEquals(0, restarted.tokensUsed(model))
        assertEquals(0, restarted.peekIndex())
    }

    @Test
    fun rotationIndexAndSpendsSurviveANewInstance() {
        val store = MemoryGroqStore()
        val rotation = rotation(store)
        rotation.recordTokens(GroqModels.CHAT[2], 40)
        rotation.commitIndex(2)
        val restarted = GroqRotation(clock, store)
        assertEquals(2, restarted.peekIndex())
        assertEquals(40, restarted.tokensUsed(GroqModels.CHAT[2]))
        val encoded = encodeSnapshot(store.read())
        assertEquals(store.read(), decodeSnapshot(encoded))
    }

    @Test
    fun aShorterCooldownDoesNotCutALongerOne() {
        val rotation = rotation()
        val model = GroqModels.CHAT[0]
        rotation.coolUntil(model, start.plus(Duration.ofHours(24)))
        rotation.coolUntil(model, start.plusSeconds(60))
        clock.instant = start.plusSeconds(61)
        assertFalse(rotation.isAvailable(model))
    }

    @Test
    fun spendsOlderThanTwentyFourHoursAreDroppedWhileNewerOnesStay() {
        val rotation = rotation()
        val model = GroqModels.CHAT[1]
        rotation.recordTokens(model, 100_000)
        clock.instant = start.plus(Duration.ofHours(23))
        rotation.recordTokens(model, 50_000)
        assertEquals(150_000, rotation.tokensUsed(model))
        clock.instant = start.plus(Duration.ofHours(24))
        assertEquals(50_000, rotation.tokensUsed(model))
        assertTrue(rotation.isAvailable(model))
    }

    private fun rotation(store: GroqStateStore = MemoryGroqStore()) = GroqRotation(clock, store)
}

class ManualClock(var instant: Instant) : GroqClock {
    override fun now(): Instant = instant
}

class MemoryGroqStore(private var snapshot: GroqSnapshot = GroqSnapshot()) : GroqStateStore {
    override fun read(): GroqSnapshot = snapshot
    override fun write(snapshot: GroqSnapshot) {
        this.snapshot = snapshot
    }
}
