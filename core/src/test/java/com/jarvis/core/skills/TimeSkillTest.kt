package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeSkillTest {
    private val clock = WallClock {
        ZonedDateTime.of(2026, 10, 23, 15, 4, 0, 0, ZoneId.of("Asia/Riyadh"))
    }
    private val skill = TimeSkill(clock)

    @Test
    fun matchesEnglishTimePhrases() {
        assertEquals("time", skill.match("what time is it")?.slots?.get("kind"))
        assertEquals("time", skill.match("What's the time?")?.slots?.get("kind"))
        assertEquals("time", skill.match("tell me the time")?.slots?.get("kind"))
        assertEquals("en", skill.match("current time")?.slots?.get("lang"))
    }

    @Test
    fun matchesEnglishDatePhrases() {
        assertEquals("date", skill.match("what's today's date")?.slots?.get("kind"))
        assertEquals("date", skill.match("what day is it today")?.slots?.get("kind"))
        assertEquals("date", skill.match("what is the date")?.slots?.get("kind"))
    }

    @Test
    fun matchesArabicTimeAndDate() {
        val time = skill.match("كم الساعة؟")
        assertEquals("time", time?.slots?.get("kind"))
        assertEquals("ar", time?.slots?.get("lang"))
        assertEquals("time", skill.match("ما هو الوقت")?.slots?.get("kind"))
        assertEquals("date", skill.match("ما التاريخ")?.slots?.get("kind"))
        assertEquals("date", skill.match("أي يوم هذا")?.slots?.get("kind"))
    }

    @Test
    fun ignoresLoosePhrases() {
        assertNull(skill.match("what time is the meeting"))
        assertNull(skill.match("set a timer for 5 minutes"))
        assertNull(skill.match("turn on the flashlight"))
        assertNull(skill.match("hello jarvis"))
    }

    @Test
    fun speaksEnglishTimeAndDate() {
        val time = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("what time is it")!!)
        }
        assertEquals("It's 3:04 PM.", time.text)

        val date = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("what is today's date")!!)
        }
        assertEquals("It's Friday, October 23, 2026.", date.text)
    }

    @Test
    fun speaksArabicTime() {
        val result = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("كم الساعة")!!)
        }
        assertTrue(result.text.contains("الساعة"))
        assertTrue(result.success)
    }
}
