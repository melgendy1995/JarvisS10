package com.jarvis.app.ui

import com.jarvis.app.debug.parseDebugOrb
import com.jarvis.orb.OrbState
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfClockTest {
    @Test
    fun dateIsDayMonthYearInRiyadh() {
        val afternoon = ZonedDateTime.of(2026, 3, 5, 9, 7, 0, 0, ShelfClock.ZONE)
        assertEquals("05/03/2026", ShelfClock.formatDate(afternoon))
        assertEquals("09:07", ShelfClock.formatTime(afternoon))
    }

    @Test
    fun nightFollowsRiyadhNotUtc() {
        val twentyThreeUtc = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, ZoneOffset.UTC)
        assertTrue(ShelfClock.isNight(twentyThreeUtc))
        assertEquals("23:00", ShelfClock.formatTime(twentyThreeUtc))

        val justBefore = ZonedDateTime.of(2026, 10, 24, 22, 59, 0, 0, ShelfClock.ZONE)
        val atNight = ZonedDateTime.of(2026, 10, 24, 23, 0, 0, 0, ShelfClock.ZONE)
        val beforeDawn = ZonedDateTime.of(2026, 10, 25, 5, 59, 0, 0, ShelfClock.ZONE)
        val dawn = ZonedDateTime.of(2026, 10, 25, 6, 0, 0, 0, ShelfClock.ZONE)
        assertFalse(ShelfClock.isNight(justBefore))
        assertTrue(ShelfClock.isNight(atNight))
        assertTrue(ShelfClock.isNight(beforeDawn))
        assertFalse(ShelfClock.isNight(dawn))

        val sixInRiyadh = ZonedDateTime.of(2026, 10, 24, 3, 0, 0, 0, ZoneOffset.UTC)
        assertFalse(ShelfClock.isNight(sixInRiyadh))
        assertEquals("06:00", ShelfClock.formatTime(sixInRiyadh))
    }

    @Test
    fun captionDirectionFollowsTheFirstStrongCharacter() {
        assertTrue(ShelfClock.prefersRtl("ما هو الطقس"))
        assertTrue(ShelfClock.prefersRtl("مرحبا ok"))
        assertFalse(ShelfClock.prefersRtl("what time is it"))
        assertFalse(ShelfClock.prefersRtl("ok مرحبا"))
        assertFalse(ShelfClock.prefersRtl(""))
        assertFalse(ShelfClock.prefersRtl("123"))
    }

    @Test
    fun debugStateNames() {
        assertEquals(OrbState.IDLE, parseDebugOrb(" idle "))
        assertEquals(OrbState.LISTENING, parseDebugOrb("LISTENING"))
        assertEquals(OrbState.THINKING, parseDebugOrb("thinking"))
        assertEquals(OrbState.SPEAKING, parseDebugOrb("Speaking"))
        assertEquals(OrbState.ERROR, parseDebugOrb("error"))
        assertNull(parseDebugOrb("asleep"))
        assertNull(parseDebugOrb(null))
    }
}
