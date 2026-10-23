package com.jarvis.provider.gemini

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ThreadLocalRandom

data class Cooldown(
    val until: Instant,
    val isDaily: Boolean,
) {
    fun remaining(now: Instant): Duration {
        val gap = Duration.between(now, until)
        return if (gap.isNegative) Duration.ZERO else gap
    }
}

interface CooldownStore {
    fun read(): Cooldown?
    fun write(cooldown: Cooldown)
    fun clear()
}

/**
 * Daily-cap wording cools Gemini until the next 00:00 America/Los_Angeles.
 * Anything else cools for 60s plus jitter. A parsed Retry-After replaces the
 * short cooldown, and it extends a daily cooldown when it lands later.
 */
object CooldownPolicy {
    fun until(
        now: Instant,
        message: String,
        retryAfter: Duration?,
        jitter: Duration,
        zone: ZoneId = ModelConfig.ZONE,
    ): Cooldown {
        val daily = QuotaSignals.isDailyCap(message)
        val base = if (daily) {
            nextMidnight(now, zone)
        } else {
            now.plusSeconds(ModelConfig.COOLDOWN_SECONDS).plus(jitter)
        }
        val honored = retryAfter?.takeIf { !it.isNegative && !it.isZero }?.let { now.plus(it) }
        val until = when {
            daily && honored != null && honored.isAfter(base) -> honored
            daily -> base
            honored != null -> honored
            else -> base
        }
        return Cooldown(until, daily)
    }

    fun nextMidnight(now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        return local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    }
}

class GeminiCooldown(
    private val time: TimeSource,
    private val store: CooldownStore,
    private val zone: ZoneId = ModelConfig.ZONE,
    private val jitterSeconds: () -> Long = {
        ThreadLocalRandom.current().nextLong(0, ModelConfig.COOLDOWN_JITTER_SECONDS + 1)
    },
) {
    private val lock = Any()

    fun active(): Cooldown? = synchronized(lock) { readActive() }

    fun isCooling(): Boolean = active() != null

    fun cool(message: String, retryAfter: Duration?): Cooldown = synchronized(lock) {
        val applied = CooldownPolicy.until(
            now = time.now(),
            message = message,
            retryAfter = retryAfter,
            jitter = Duration.ofSeconds(jitterSeconds().coerceAtLeast(0)),
            zone = zone,
        )
        store.write(applied)
        applied
    }

    private fun readActive(): Cooldown? {
        val stored = store.read() ?: return null
        if (!stored.until.isAfter(time.now())) {
            store.clear()
            return null
        }
        return stored
    }
}
