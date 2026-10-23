package com.jarvis.orb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OrbGeometryTest {
    @Test
    fun cloudHasAStableShell() {
        val points = buildPointCloud()
        assertEquals(ORB_POINT_COUNT * 4, points.size)
        var shell = 0
        for (i in 0 until ORB_POINT_COUNT) {
            val x = points[i * 4]
            val y = points[i * 4 + 1]
            val z = points[i * 4 + 2]
            val w = points[i * 4 + 3]
            assertTrue(x.isFinite() && y.isFinite() && z.isFinite())
            assertTrue(w in 0f..1f)
            val radius = sqrt(x * x + y * y + z * z)
            if (radius > 0.7f) shell++
        }
        assertTrue(shell > ORB_POINT_COUNT / 2)
        val again = buildPointCloud()
        assertEquals(points[0], again[0], 0f)
        assertEquals(points[points.size - 1], again[points.size - 1], 0f)
    }
}
