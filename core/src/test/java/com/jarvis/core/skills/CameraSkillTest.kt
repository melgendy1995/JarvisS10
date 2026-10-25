package com.jarvis.core.skills

import com.jarvis.core.CommandRouter
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
    fun openAndShowPhrasesStartTheStream() {
        for (phrase in listOf(
            "open camera",
            "open the camera",
            "open the front camera",
            "open front camera",
            "open the back camera",
            "show camera",
            "show the camera",
            "show me the camera",
            "start camera",
            "start the camera",
            "start front camera",
            "turn on the rear camera",
            "turn on the front camera",
        )) {
            val call = skill.match(phrase)
            assertEquals(phrase, "on", call?.slots?.get("action"))
        }
    }

    @Test
    fun arabicOpenAndLensPhrasesStartTheStream() {
        for (phrase in listOf(
            "افتح الكاميرا",
            "شغل الكاميرا",
            "افتح الكاميرا الأمامية",
            "شغل الكاميرا الخلفية",
            "شغل الكاميرا الامامية",
        )) {
            val call = skill.match(phrase)
            assertEquals(phrase, "on", call?.slots?.get("action"))
        }
    }

    @Test
    fun lensDefaultsToFront() {
        assertEquals("front", skill.match("open camera")?.slots?.get("lens"))
        assertEquals("front", skill.match("camera on")?.slots?.get("lens"))
        assertEquals("front", skill.match("افتح الكاميرا")?.slots?.get("lens"))
        assertEquals("front", skill.match("turn the camera on")?.slots?.get("lens"))
    }

    @Test
    fun lensParsesFrontAndBack() {
        assertEquals("front", skill.match("open front camera")?.slots?.get("lens"))
        assertEquals("front", skill.match("turn on the front camera")?.slots?.get("lens"))
        assertEquals("front", skill.match("افتح الكاميرا الأمامية")?.slots?.get("lens"))
        assertEquals("front", skill.match("شغل الكاميرا الامامية")?.slots?.get("lens"))
        assertEquals("back", skill.match("open the back camera")?.slots?.get("lens"))
        assertEquals("back", skill.match("turn on the rear camera")?.slots?.get("lens"))
        assertEquals("back", skill.match("start back camera")?.slots?.get("lens"))
        assertEquals("back", skill.match("افتح الكاميرا الخلفية")?.slots?.get("lens"))
        assertEquals("back", skill.match("شغل الكاميرا الخلفية")?.slots?.get("lens"))
        assertNull(skill.match("camera off")?.slots?.get("lens"))
    }

    @Test
    fun runsStartWithLens() = runBlocking {
        camera.outcome = CameraOutcome.STARTED
        assertEquals("Back camera on.", skill.run(skill.match("open the back camera")!!).text)
        assertEquals("back", camera.lastLens)
        assertEquals("Front camera on.", skill.run(skill.match("open camera")!!).text)
        assertEquals("front", camera.lastLens)
        assertEquals("الكاميرا شغالة.", skill.run(skill.match("شغل الكاميرا الخلفية")!!
            .also { assertEquals("ar", it.slots["lang"]) }).text)
        assertEquals("back", camera.lastLens)
    }

    @Test
    fun ignoresNearbyPhrases() {
        assertNull(skill.match("turn on the flashlight"))
        assertNull(skill.match("start the timer"))
        assertNull(skill.match("what time is it"))
        assertNull(skill.match("شغل الكشاف"))
        assertNull(skill.match("camera"))
        assertNull(skill.match("open the camera roll"))
    }

    @Test
    fun openCameraIsNotAnAppLaunch() {
        // The app launcher must not claim camera phrases.
        assertNull(AppControlPhrases.parse("open camera"))
        assertNull(AppControlPhrases.parse("open the camera"))
        assertNull(AppControlPhrases.parse("افتح الكاميرا"))
        assertNull(AppControlPhrases.parse("start front camera"))
    }

    @Test
    fun routerSendsOpenCameraToTheStreamNotTheLauncher() = runBlocking {
        val registry = SkillRegistry()
        registry.register(CameraSkill(camera))
        registry.register(AppControlSkill(AppLauncher()))
        val router = CommandRouter(registry)
        val result = router.route("open camera")
        assertEquals("Front camera on.", result.text)
        assertTrue(result.success)
        assertEquals("on", camera.last)
        assertEquals("front", camera.lastLens)
        assertFalse(LauncherSpy.called)
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
        assertEquals("Front camera on.", skill.run(skill.match("camera on")!!).text)
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
    var lastLens: String? = null

    override suspend fun turnOn(lens: CameraLens): CameraOutcome {
        last = "on"
        lastLens = lens.name.lowercase()
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

object LauncherSpy {
    var called = false
}

private class AppLauncher : AppControl {
    override suspend fun perform(call: SkillCall): SkillResult {
        LauncherSpy.called = true
        return SkillResult(text = "Opened camera.", success = true)
    }
}
