package com.jarvis.app.system

import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.Utterances
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera status skill is read-only; match() runs it for watching questions only. */
class CameraStatusSkillTest {
    private val skill = CameraStatusSkill(status = { "The camera is on, and one viewer is connected." })

    private fun calls(text: String): SkillCall? = skill.match(text)

    @Test
    fun englishWatchingQuestionsMatch() {
        assertTrue(calls("Is anyone watching the camera feed") != null)
        assertTrue(calls("Anyone is watching camera feed") != null)
        assertTrue(calls("is anyone watching") != null)
        assertTrue(calls("Who is watching the camera stream?") != null)
        assertTrue(calls("are we watching the camera") != null)
    }

    @Test
    fun arabicWatchingQuestionsMatch() {
        assertTrue(calls("في احد يشوف الكاميرا؟") != null)
        assertTrue(calls("في احد يطالع كاميرا") != null)
    }

    @Test
    fun controlsNeverMatch() {
        assertNull(calls("stop watching the camera"))
        assertNull(calls("turn off the camera"))
        assertNull(calls("turn on the camera"))
        assertNull(calls("open camera"))
    }

    @Test
    fun runReportsWithoutChangingAnything() = runBlocking {
        val result = skill.run(calls("is anyone watching the camera")!!)
        assertEquals("The camera is on, and one viewer is connected.", result.text)
        assertTrue(result.success)
    }

    @Test
    fun normalizeFoldingWorksBeforeMatching() {
        val normalized = Utterances.normalize("Is Anyone Watching!")
        assertTrue(CameraStatusSkill.isStatusQuestion(normalized))
    }
}
