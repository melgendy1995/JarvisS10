package com.jarvis.core

import com.jarvis.core.skills.FlashlightSkill
import com.jarvis.core.skills.Skill
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import com.jarvis.core.skills.TimeSkill
import com.jarvis.core.skills.TimerScheduler
import com.jarvis.core.skills.TimerHandle
import com.jarvis.core.skills.TimerSkill
import com.jarvis.core.skills.TorchController
import com.jarvis.core.skills.TorchStatus
import com.jarvis.core.skills.WallClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CommandRouterTest {
    private val scheduler = object : TimerScheduler {
        val delays = mutableListOf<Long>()
        override fun schedule(delayMillis: Long, message: String): TimerHandle {
            delays += delayMillis
            return TimerHandle {}
        }
        override fun cancelAll(): Int = 0
    }
    private val torch = TorchController { TorchStatus.ON }
    private val registry = SkillRegistry().apply {
        register(TimeSkill(WallClock {
            ZonedDateTime.of(2026, 10, 23, 15, 4, 0, 0, ZoneId.of("Asia/Riyadh"))
        }))
        register(TimerSkill(scheduler))
        register(FlashlightSkill(torch))
    }
    private val router = CommandRouter(registry)

    @Test
    fun routesTime() = runBlocking {
        val result = router.route("what time is it")
        assertEquals("It's 3:04 PM.", result.text)
        assertTrue(result.success)
    }

    @Test
    fun routesArabicTime() = runBlocking {
        val result = router.route("كم الساعة")
        assertTrue(result.text.startsWith("الساعة"))
    }

    @Test
    fun timerDoesNotGetEatenByTime() = runBlocking {
        val result = router.route("set a timer for 5 minutes")
        assertEquals("Timer set for 5 minutes.", result.text)
        assertEquals(listOf(300_000L), scheduler.delays)
    }

    @Test
    fun routesFlashlight() = runBlocking {
        val result = router.route("turn on the flashlight")
        assertEquals("Flashlight on.", result.text)
    }

    @Test
    fun unknownUtteranceHasNoBrainYet() = runBlocking {
        val result = router.route("tell me a joke")
        assertFalse(result.success)
        assertTrue(result.text.contains("no brain yet", ignoreCase = true))
    }

    @Test
    fun blankUtteranceIsAMiss() = runBlocking {
        val result = router.route("   ")
        assertEquals("I didn't catch that.", result.text)
    }

    @Test
    fun firstRegisteredSkillWins() = runBlocking {
        val registry = SkillRegistry()
        registry.register(stub("first", "same words", "from first"))
        registry.register(stub("second", "same words", "from second"))
        val result = CommandRouter(registry).route("same words")
        assertEquals("from first", result.text)
    }

    @Test
    fun declarationsCarryTheSkillId() {
        val time = registry.registered().first { it.id == "time" }
        val declaration = time.geminiDeclaration() as Map<*, *>
        assertEquals("time", declaration["name"])
        val groq = time.groqTool() as Map<*, *>
        assertEquals("function", groq["type"])
        assertEquals("time", (groq["function"] as Map<*, *>)["name"])
    }

    private fun stub(id: String, phrase: String, speech: String): Skill = object : Skill {
        override val id: String = id
        override fun match(text: String): SkillCall? {
            return if (text.trim() == phrase) SkillCall(id, text) else null
        }
        override fun geminiDeclaration(): Any? = null
        override fun groqTool(): Any? = null
        override suspend fun run(call: SkillCall) = SkillResult(speech)
    }
}
