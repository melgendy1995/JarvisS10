package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeLevelsTest {
    @Test
    fun percentAndLevel() {
        assertEquals(8, VolumeLevels.resolve(0, 15, 50, "percent"))
        assertEquals(15, VolumeLevels.resolve(0, 15, 100, "percent"))
        assertEquals(0, VolumeLevels.resolve(0, 15, 0, "percent"))
        assertEquals(10, VolumeLevels.resolve(0, 15, 10, "level"))
        assertEquals(10, VolumeLevels.resolve(0, 15, 10, "auto"))
        assertEquals(8, VolumeLevels.resolve(0, 15, 50, "auto"))
        assertEquals(15, VolumeLevels.resolve(0, 15, 40, "level"))
    }
}
