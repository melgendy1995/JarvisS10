package com.jarvis.core.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSkillTest {
    private val camera = FakeCamera()
    private val skill = CameraSkill(camera)

    @Test
    fun matchesEnglish() {
        assertEquals("on", skill.match("camera on")?.slots?.get("action"))
        assertEquals("on", skill.match("turn on the camera")?.slots?.get("action"))
        assertEquals("on", skill.match("turn the camera on")?.slots?.get("action"))
        assertEquals("on", skill.match("please start the camera")?.slots?.get("action"))
        assertEquals("off", skill.match("camera off")?.slots?.get("action"))
        assertEquals("off", skill.match("turn off the camera")?.slots?.get("action"))
        assertEquals("off", skill.match("stop the camera")?.slots?.get("action"))
        assertEquals("switch", skill.match("switch camera")?.slots?.get("action"))
        assertEquals("switch", skill.match("switch the camera")?.slots?.get("action"))
        assertEquals("switch", skill.match("flip the camera")?.slots?.get("action"))
        assertEquals("en", skill.match("camera on")?.slots?.get("lang"))
    }

    @Test
    fun matchesArabic() {
        val on = skill.match("شغل الكاميرا")
        assertEquals("on", on?.slots?.get("action"))
        assertEquals("ar", on?.slots?.get("lang"))
        assertEquals("off", skill.match("اطفي الكاميرا")?.slots?.get("action"))
        assertEquals("off", skill.match("أطفئ الكاميرا")?.slots?.get("action"))
        assertEquals("switch", skill.match("بدل الكاميرا")?.slots?.get("action"))
        assertEquals("switch", skill.match("بدّل الكاميرا")?.slots?.get("action"))
    }

    @Test
    fun ignoresNearbyPhrases() {
        assertNull(skill.match("turn on the flashlight"))
        assertNull(skill.match("open the camera"))
        assertNull(skill.match("start the timer"))
        assertNull(skill.match("what time is it"))
        assertNull(skill.match("شغل الكشاف"))
        assertNull(skill.match("camera"))
    }

    @Test
    fun speaksPasswordAndPermission() = runBlocking {
        camera.outcome = CameraOutcome.NO_PASSWORD
        val missing = skill.run(skill.match("camera on")!!)
        assertEquals("Set a camera password in settings first.", missing.text)
        assertFalse(missing.success)

        camera.outcome = CameraOutcome.NEEDS_PERMISSION
        val needs = skill.run(skill.match("شغل الكاميرا")!!)
        assertEquals("android.permission.CAMERA", needs.needsPermission)
        assertTrue(needs.text.contains("الكاميرا"))

        camera.outcome = CameraOutcome.THERMAL_STOP
        val hot = skill.run(skill.match("camera on")!!)
        assertEquals("The phone is too hot, so I stopped the camera.", hot.text)
    }

    @Test
    fun runsOnOffAndSwitch() = runBlocking {
        camera.outcome = CameraOutcome.STARTED
        assertEquals("Camera on.", skill.run(skill.match("camera on")!!).text)
        assertEquals("on", camera.last)

        camera.outcome = CameraOutcome.STOPPED
        assertEquals("Camera off.", skill.run(skill.match("camera off")!!).text)

        camera.outcome = CameraOutcome.SWITCHED_FRONT
        assertEquals("Switched to the front camera.", skill.run(skill.match("switch camera")!!).text)

        camera.outcome = CameraOutcome.STOPPED
        assertEquals("الكاميرا طفت.", skill.run(skill.match("اطفي الكاميرا")!!).text)
    }
}

private class FakeCamera : CameraController {
    var outcome = CameraOutcome.STARTED
    var last: String? = null

    override suspend fun turnOn(): CameraOutcome {
        last = "on"
        return outcome
    }

    override suspend fun turnOff(): CameraOutcome {
        last = "off"
        return outcome
    }

    override suspend fun switchCamera(): CameraOutcome {
        last = "switch"
        return outcome
    }
}
