package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSkillTest {
    private val scheduler = FakeTimerScheduler()
    private val skill = TimerSkill(scheduler)

    @Test
    fun setsMinutesSecondsAndHours() {
        assertScheduled("set a timer for 5 minutes", 5 * 60_000L, "Timer set for 5 minutes.")
        assertScheduled("timer for 30 seconds", 30_000L, "Timer set for 30 seconds.")
        assertScheduled("start a timer for 1 hour", 3_600_000L, "Timer set for 1 hour.")
        assertScheduled(
            "set a timer for 2 hours and 15 minutes",
            2 * 3_600_000L + 15 * 60_000L,
            "Timer set for 2 hours and 15 minutes.",
        )
    }

    @Test
    fun parsesWordsBareNumbersAndEmbeddedDuration() {
        assertScheduled("set a timer for forty five seconds", 45_000L, "Timer set for 45 seconds.")
        assertScheduled("set a timer for an hour", 3_600_000L, "Timer set for 1 hour.")
        assertScheduled("set a timer for half an hour", 30 * 60_000L, "Timer set for 30 minutes.")
        assertScheduled("set a timer for 5", 5 * 60_000L, "Timer set for 5 minutes.")
        assertScheduled("start a 90 second timer", 90_000L, "Timer set for 1 minute and 30 seconds.")
    }

    @Test
    fun parsesArabicDurations() {
        assertScheduled("مؤقت لمدة 5 دقائق", 5 * 60_000L, "تم ضبط المؤقت لمدة 5 دقيقه.")
        assertScheduled("اضبط مؤقت 10 ثواني", 10_000L, "تم ضبط المؤقت لمدة 10 ثانيه.")
        assertScheduled("مؤقت لمدة خمس دقائق", 5 * 60_000L, "تم ضبط المؤقت لمدة 5 دقيقه.")
        val done = skill.match("مؤقت لمدة 5 دقائق")
        assertEquals("ar", done?.slots?.get("lang"))
        assertEquals("انتهى المؤقت.", done?.slots?.get("done"))
    }

    @Test
    fun asksWhenTheDurationIsMissing() {
        val call = skill.match("set a timer")
        assertEquals("missing", call?.slots?.get("action"))
        val result = kotlinx.coroutines.runBlocking { skill.run(call!!) }
        assertEquals("For how long?", result.text)
    }

    @Test
    fun cancelsAndReportsWhenNothingIsRunning() {
        val cancel = skill.match("cancel the timer")
        assertEquals("cancel", cancel?.slots?.get("action"))
        val empty = kotlinx.coroutines.runBlocking { skill.run(cancel!!) }
        assertEquals("No timer is running.", empty.text)

        skillRun("set a timer for 1 minute")
        val result = kotlinx.coroutines.runBlocking { skill.run(skill.match("stop timer")!!) }
        assertEquals("Timer cancelled.", result.text)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun doesNotStealTimeQuestions() {
        assertNull(skill.match("what time is it"))
        assertNull(skill.match("what's the time"))
        assertNull(skill.match("turn on the flashlight"))
    }

    @Test
    fun clampsAtTwentyFourHours() {
        assertScheduled("set a timer for 48 hours", 24 * 3_600_000L, "Timer set for 24 hours.")
    }

    @Test
    fun announcementIsTheDoneMessage() {
        skillRun("timer for 10 seconds")
        assertEquals("Timer done.", scheduler.scheduled.single().second)
    }

    private fun assertScheduled(phrase: String, millis: Long, confirm: String) {
        scheduler.scheduled.clear()
        val result = skillRun(phrase)
        assertEquals(confirm, result.text)
        assertEquals(millis, scheduler.scheduled.single().first)
    }

    private fun skillRun(phrase: String): SkillResult {
        val call = skill.match(phrase) ?: error("no match for [$phrase]")
        return kotlinx.coroutines.runBlocking { skill.run(call) }
    }
}

private class FakeTimerScheduler : TimerScheduler {
    val scheduled = mutableListOf<Pair<Long, String>>()

    override fun schedule(delayMillis: Long, message: String): TimerHandle {
        scheduled += delayMillis to message
        return TimerHandle { scheduled.remove(delayMillis to message) }
    }

    override fun cancelAll(): Int {
        val count = scheduled.size
        scheduled.clear()
        return count
    }
}
