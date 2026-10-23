package com.jarvis.provider.gemini

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAndCooldownTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val evening = zoned(2026, 10, 23, 23, 30)
    private val morning = zoned(2026, 10, 24, 0, 5)

    @Test
    fun countsTurnsSecondsAndTextUntilTheCap() {
        val time = MutableTime(evening)
        val counter = UsageCounter(time, MemoryUsage(), zone = zone, liveTurnCap = 40)
        repeat(39) { counter.recordLiveTurn() }
        assertFalse(counter.liveCapReached())
        counter.recordLiveTurn()
        assertTrue(counter.liveCapReached())
        counter.addLiveSeconds(12)
        counter.recordTextRequest()
        counter.recordTextRequest()
        val usage = counter.snapshot()
        assertEquals(40, usage.liveTurns)
        assertEquals(12L, usage.liveSeconds)
        assertEquals(2, usage.textRequests)
        assertEquals(LocalDate.of(2026, 10, 23), usage.day)
    }

    @Test
    fun capIsConfigurable() {
        val counter = UsageCounter(MutableTime(evening), MemoryUsage(), zone = zone, liveTurnCap = 2)
        counter.recordLiveTurn()
        assertFalse(counter.liveCapReached())
        counter.recordLiveTurn()
        assertTrue(counter.liveCapReached())
    }

    @Test
    fun countersResetAtPacificMidnightAndSurviveANewInstance() {
        val time = MutableTime(evening)
        val store = MemoryUsage()
        val first = UsageCounter(time, store, zone = zone)
        first.recordLiveTurn()
        first.addLiveSeconds(5)
        first.recordTextRequest()
        time.instant = morning
        assertEquals(0, first.snapshot().liveTurns)
        assertEquals(0L, first.snapshot().liveSeconds)
        assertEquals(0, first.snapshot().textRequests)
        assertFalse(first.liveCapReached())
        first.recordTextRequest()
        val restarted = UsageCounter(time, store, zone = zone)
        assertEquals(1, restarted.snapshot().textRequests)
        assertEquals(0, restarted.snapshot().liveTurns)
        assertEquals(LocalDate.of(2026, 10, 24), restarted.snapshot().day)
    }

    @Test
    fun zeroSecondsAreIgnored() {
        val counter = UsageCounter(MutableTime(evening), MemoryUsage(), zone = zone)
        counter.addLiveSeconds(0)
        counter.addLiveSeconds(-3)
        assertEquals(0L, counter.snapshot().liveSeconds)
    }

    @Test
    fun dailyWordingCoolsUntilNextPacificMidnight() {
        val now = evening
        val cooldown = CooldownPolicy.until(
            now = now,
            message = "Quota exceeded for quota metric 'Generate Content API requests per day'",
            retryAfter = null,
            jitter = Duration.ofSeconds(7),
            zone = zone,
        )
        assertTrue(cooldown.isDaily)
        assertEquals(zoned(2026, 10, 24, 0, 0), cooldown.until)
    }

    @Test
    fun rpmStyleCoolsSixtySecondsPlusJitter() {
        val now = evening
        val cooldown = CooldownPolicy.until(
            now = now,
            message = "You exceeded your current quota, please check your plan and billing details.",
            retryAfter = null,
            jitter = Duration.ofSeconds(7),
            zone = zone,
        )
        assertFalse(cooldown.isDaily)
        assertEquals(now.plusSeconds(67), cooldown.until)
    }

    @Test
    fun retryAfterReplacesTheShortCooldown() {
        val now = evening
        val cooldown = CooldownPolicy.until(
            now = now,
            message = "RESOURCE_EXHAUSTED",
            retryAfter = Duration.ofSeconds(10),
            jitter = Duration.ofSeconds(15),
            zone = zone,
        )
        assertFalse(cooldown.isDaily)
        assertEquals(now.plusSeconds(10), cooldown.until)
    }

    @Test
    fun retryAfterExtendsADailyCooldownWhenItIsLater() {
        val now = zoned(2026, 10, 23, 22, 30)
        val cooldown = CooldownPolicy.until(
            now = now,
            message = "daily quota exhausted",
            retryAfter = Duration.ofHours(3),
            jitter = Duration.ZERO,
            zone = zone,
        )
        assertTrue(cooldown.isDaily)
        assertEquals(now.plus(Duration.ofHours(3)), cooldown.until)
    }

    @Test
    fun persistedCooldownSurvivesANewInstanceAndExpires() {
        val time = MutableTime(evening)
        val store = MemoryCooldown()
        val first = GeminiCooldown(time, store, zone = zone, jitterSeconds = { 4 })
        val applied = first.cool("quota", retryAfter = Duration.ofSeconds(30))
        assertEquals(evening.plusSeconds(30), applied.until)
        val restarted = GeminiCooldown(time, store, zone = zone, jitterSeconds = { 0 })
        assertTrue(restarted.isCooling())
        assertEquals(Duration.ofSeconds(30), restarted.active()!!.remaining(time.now()))
        time.instant = evening.plusSeconds(31)
        assertFalse(restarted.isCooling())
        assertNull(restarted.active())
        assertNull(store.read())
    }

    @Test
    fun rateLimitAndRetryAfterParsing() {
        val quota = RuntimeException("RESOURCE_EXHAUSTED: Quota exceeded")
        assertTrue(QuotaSignals.isRateLimit(quota))
        assertTrue(QuotaSignals.isDailyCap("requests per day"))
        assertTrue(QuotaSignals.isDailyCap("GenerateRequestsPerDayPerProject"))
        assertFalse(QuotaSignals.isDailyCap("You exceeded your current quota"))
        assertFalse(QuotaSignals.isRateLimit(RuntimeException("socket closed")))
        assertEquals(Duration.ofSeconds(90), QuotaSignals.retryAfter("Retry-After: 90"))
        assertEquals(Duration.ofSeconds(45), QuotaSignals.retryAfter("""{"retryDelay": "45s"}"""))
        assertEquals(Duration.ofSeconds(120), QuotaSignals.retryAfter("Please retry after 2 minutes"))
        assertTrue(QuotaSignals.isRateLimit(RuntimeException("HTTP 429")))
        val wrapped = RuntimeException("upstream", object : RuntimeException("check quota") {})
        assertTrue(QuotaSignals.isRateLimit(wrapped))
        assertTrue(QuotaSignals.isNotConfigured(RuntimeException("The Gemini Developer API is not enabled")))
        assertFalse(QuotaSignals.isNotConfigured(quota))
    }

    @Test
    fun reconnectsOnceUnlessTheCloseWasIntentional() {
        assertTrue(LiveReconnect.allow(alreadyReconnected = false, intentionalClose = false))
        assertFalse(LiveReconnect.allow(alreadyReconnected = true, intentionalClose = false))
        assertFalse(LiveReconnect.allow(alreadyReconnected = false, intentionalClose = true))
    }

    @Test
    fun liveCircuitSkipsForFiveMinutesThenOpens() {
        val circuit = LiveCircuit(windowMs = 5 * 60_000L)
        assertFalse(circuit.skip(1_000))
        circuit.mark(1_000)
        assertTrue(circuit.skip(1_000))
        assertTrue(circuit.skip(1_000 + 5 * 60_000L - 1))
        assertFalse(circuit.skip(1_000 + 5 * 60_000L))
        circuit.clear()
        assertFalse(circuit.skip(9_000))
    }

    @Test
    fun causeChainRedactsSecretsAndDetectsARejectedModel() {
        val rejected = IllegalArgumentException(
            "models/gemini-3.1-flash-live-preview was not found Bearer secret ya29.token gsk_live_secret",
        )
        val chain = rejected.causeChain()
        assertTrue(chain.contains("was not found"))
        assertTrue(chain.contains("Bearer [redacted]"))
        assertTrue(chain.contains("ya29.[redacted]"))
        assertTrue(chain.contains("gsk_[redacted]"))
        assertFalse(chain.contains("ya29.token"))
        assertFalse(chain.contains("gsk_live"))
        assertTrue(rejected.isModelRejected())
        assertTrue(RuntimeException("Channel was cancelled", rejected).isModelRejected())
        assertFalse(IllegalStateException("Channel was cancelled").isModelRejected())
    }

    @Test
    fun transcriptsAcceptCumulativeAndPiecewiseChunks() {
        val text = StringBuilder()
        Transcripts.append(text, "Hello")
        Transcripts.append(text, "Hello there")
        Transcripts.append(text, "friend")
        assertEquals("Hello there friend", text.toString())
        Transcripts.append(text, "friend")
        assertEquals("Hello there friend", text.toString())
    }

    private fun zoned(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()
    }

    private class MutableTime(var instant: Instant) : TimeSource {
        override fun now(): Instant = instant
    }

    private class MemoryUsage : UsageStore {
        var value: Usage? = null
        override fun read(): Usage? = value
        override fun write(usage: Usage) {
            value = usage
        }
    }

    private class MemoryCooldown : CooldownStore {
        var value: Cooldown? = null
        override fun read(): Cooldown? = value
        override fun write(cooldown: Cooldown) {
            value = cooldown
        }
        override fun clear() {
            value = null
        }
    }
}
