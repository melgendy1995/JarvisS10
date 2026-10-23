package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashlightSkillTest {
    private val torch = FakeTorch()
    private val skill = FlashlightSkill(torch)

    @Test
    fun matchesOnAndOff() {
        assertEquals("true", skill.match("turn on the flashlight")?.slots?.get("enabled"))
        assertEquals("false", skill.match("turn the flashlight off")?.slots?.get("enabled"))
        assertEquals("true", skill.match("torch on")?.slots?.get("enabled"))
        assertEquals("false", skill.match("switch off the torch")?.slots?.get("enabled"))
        assertEquals("true", skill.match("please enable the flashlight")?.slots?.get("enabled"))
    }

    @Test
    fun matchesArabic() {
        val on = skill.match("شغل الكشاف")
        assertEquals("true", on?.slots?.get("enabled"))
        assertEquals("ar", on?.slots?.get("lang"))
        assertEquals("false", skill.match("أطفئ الكشاف")?.slots?.get("enabled"))
    }

    @Test
    fun ignoresOtherLights() {
        assertNull(skill.match("turn on the lights"))
        assertNull(skill.match("turn on the kitchen lights"))
        assertNull(skill.match("what time is it"))
        assertNull(skill.match("set a timer for 5 minutes"))
    }

    @Test
    fun runsTheTorch() {
        val on = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("turn on the flashlight")!!)
        }
        assertEquals("Flashlight on.", on.text)
        assertTrue(torch.on)

        val off = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("turn off the flashlight")!!)
        }
        assertEquals("Flashlight off.", off.text)
        assertFalse(torch.on)
    }

    @Test
    fun reportsPermissionAndUnavailable() {
        torch.status = TorchStatus.NEEDS_PERMISSION
        val needs = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("flashlight on")!!)
        }
        assertEquals("android.permission.CAMERA", needs.needsPermission)
        assertFalse(needs.success)

        torch.status = TorchStatus.UNAVAILABLE
        val missing = kotlinx.coroutines.runBlocking {
            skill.run(skill.match("flashlight off")!!)
        }
        assertEquals("I can't reach the flashlight.", missing.text)
    }
}

private class FakeTorch : TorchController {
    var on: Boolean = false
    var status: TorchStatus? = null

    override fun setEnabled(on: Boolean): TorchStatus {
        this.on = on
        return status ?: if (on) TorchStatus.ON else TorchStatus.OFF
    }
}
