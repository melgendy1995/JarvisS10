package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Order, never-throwing and one-shot behaviour of the teardown sequence. */
class TeardownSequenceTest {
    private val lines = ArrayList<String>()
    private var now = 0L
    private fun clock() = now

    private fun sequence() = TeardownSequence("test", { line -> lines += line }, ::clock)

    @Test
    fun stepsRunInGivenOrder() {
        val touched = ArrayList<String>()
        val torn = sequence()
        torn.run(
            "a" to { touched += "a" },
            "b" to { touched += "b" },
            "c" to { touched += "c" },
        )
        assertEquals(listOf("a", "b", "c"), touched)
    }

    @Test
    fun aFailingStepDoesNotStopTheRest() {
        val touched = ArrayList<String>()
        val torn = sequence()
        torn.run(
            "good" to { touched += "good" },
            "bad" to { throw IllegalStateException("native boom") },
            "last" to { touched += "last" },
        )
        assertEquals(listOf("good", "last"), touched)
        assertTrue(lines.any { it.contains("step=bad failed=IllegalStateException") })
        assertTrue(lines.any { it.contains("step=last") && !it.contains("failed") })
    }

    @Test
    fun aSecondRunIsANoOp() {
        val touched = ArrayList<String>()
        val torn = sequence()
        torn.run("a" to { touched += "a" })
        torn.run("a" to { touched += "a-again" }, "b" to { touched += "b" })
        assertEquals(listOf("a"), touched)
    }

    @Test
    fun beginAndDoneAreLoggedOnceWithTheTotalDuration() {
        val torn = sequence()
        torn.run("a" to { now = 12L }, "b" to { now = 40L })
        torn.run("c" to { now = 100L })
        assertEquals(1, lines.count { it.contains("teardown begin") })
        assertEquals(1, lines.count({ it.contains("teardown done ms=") }))
        assertTrue(lines.last().contains("teardown done ms=40"))
    }

    @Test
    fun everyStepIsLoggedOnce() {
        val torn = sequence()
        torn.run("x" to { }, "y" to { })
        torn.run("z" to { })
        assertEquals(listOf("x", "y"), lines.filter { it.contains("teardown step=") }.map {
            it.substringAfter("step=").substringBefore(" ")
        })
    }
}
