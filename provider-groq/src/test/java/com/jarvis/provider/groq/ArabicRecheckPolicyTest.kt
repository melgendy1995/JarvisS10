package com.jarvis.provider.groq

import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicRecheckPolicyTest {
    private val arabic = "شغّل سورة الكهف على يوتيوب ميوزك"

    @Test
    fun `whisper not ready means no recheck`() {
        val recheck = ArabicRecheckPolicy.shouldRecheck(
            whisperReady = false,
            pcmBytes = 32_000,
            recognizerText = "Shuffle",
            matchesLocalSkill = false,
            confidence = 0.7f,
        )
        assertFalse(recheck)
    }

    @Test
    fun `short pcm means no recheck`() {
        val recheck = ArabicRecheckPolicy.shouldRecheck(
            whisperReady = true,
            pcmBytes = 100,
            recognizerText = "Shuffle",
            matchesLocalSkill = false,
            confidence = 0.7f,
        )
        assertFalse(recheck)
    }

    @Test
    fun `arabic text means no recheck`() {
        val recheck = ArabicRecheckPolicy.shouldRecheck(
            whisperReady = true,
            pcmBytes = 32_000,
            recognizerText = arabic,
            matchesLocalSkill = false,
            confidence = 0.7f,
        )
        assertFalse(recheck)
    }

    @Test
    fun `unmatched english text means recheck`() {
        val recheck = ArabicRecheckPolicy.shouldRecheck(
            whisperReady = true,
            pcmBytes = 32_000,
            recognizerText = "Shuffle",
            matchesLocalSkill = false,
            confidence = 0.7f,
        )
        assertTrue(recheck)
    }

    @Test
    fun `low confidence means recheck`() {
        for (confidence in listOf(0f, 0.3f, 0.59f)) {
            val recheck = ArabicRecheckPolicy.shouldRecheck(
                whisperReady = true,
                pcmBytes = 32_000,
                recognizerText = "Shuffle",
                matchesLocalSkill = true,
                confidence = confidence,
            )
            assertTrue("confidence=$confidence", recheck)
        }
    }

    @Test
    fun `matching skill without run keeps high confidence fast`() {
        for (confidence in listOf(-1f, 0.6f, 0.95f)) {
            val recheck = ArabicRecheckPolicy.shouldRecheck(
                whisperReady = true,
                pcmBytes = 32_000,
                recognizerText = "What time is it",
                matchesLocalSkill = true,
                confidence = confidence,
            )
            assertFalse("confidence=$confidence", recheck)
        }
    }

    @Test
    fun `registry match reports skills without running them`() {
        var runs = 0
        val registry = SkillRegistry()
        registry.register(
            object : com.jarvis.core.skills.Skill {
                override val id = "time"
                override fun match(text: String): SkillCall? = if (text in setOf("What time is it")) {
                    SkillCall(skillId = id, utterance = text)
                } else {
                    null
                }

                override fun geminiDeclaration(): Any? = null
                override fun groqTool(): Any? = null
                override suspend fun run(call: SkillCall): SkillResult {
                    runs++
                    return SkillResult(text = "It is 6:00.", success = true)
                }
            },
        )
        assertEquals("What time is it", registry.match("What time is it")?.call?.utterance)
        assertEquals(0, runs)
        assertTrue(registry.match("Shuffle") == null)
        assertEquals(0, runs)
    }

    @Test
    fun `choose text only uses arabic whisper results`() {
        assertEquals(
            "شغّل سورة الكهف",
            ArabicRecheckPolicy.chooseText("Shuffle", "شغّل سورة الكهف"),
        )
        assertEquals("Shuffle", ArabicRecheckPolicy.chooseText("Shuffle", "Shuffle the deck"))
        assertEquals("Shuffle", ArabicRecheckPolicy.chooseText("Shuffle", null))
    }

    @Test
    fun `has arabic detects arabic letters only`() {
        assertTrue(ArabicRecheckPolicy.hasArabic(arabic))
        assertTrue(ArabicRecheckPolicy.hasArabic("shuf\u0641le"))
        assertFalse(ArabicRecheckPolicy.hasArabic("Shuffle"))
        assertFalse(ArabicRecheckPolicy.hasArabic(null))
    }
}
