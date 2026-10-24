package com.jarvis.core.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSkillTest {
    @Test
    fun matchesWeatherAndLeavesNewsAlone() {
        val skill = skill(permitted = false, ask = false)
        assertEquals("en", skill.match("what's the weather now")?.slots?.get("lang"))
        assertEquals("en", skill.match("How hot is it?")?.slots?.get("lang"))
        assertEquals("ar", skill.match("كيف الطقس الآن")?.slots?.get("lang"))
        assertEquals("ar", skill.match("كم درجة الحرارة")?.slots?.get("lang"))
        assertNull(skill.match("what's the latest news about Saudi Arabia"))
        assertNull(skill.match("what is the price of gold today"))
        assertNull(skill.match("what time is it"))
    }

    @Test
    fun deniedLocationSpeaksRiyadhAndAsksOnce() = runBlocking {
        val fixes = FakeFix(permitted = false, shouldAsk = true)
        val skill = WeatherSkill(fixedClient(), fixes) { }
        val result = skill.run(skill.match("what's the weather now")!!)
        assertEquals(
            "In Riyadh, it's 28 degrees and clear. Today's high is 34 and the low is 22.",
            result.text,
        )
        assertEquals("Riyadh 28°", result.shelf)
        assertEquals(WeatherSkill.COARSE_LOCATION_PERMISSION, result.needsPermission)
        assertTrue(fixes.asked)
        assertTrue(result.success)
    }

    @Test
    fun grantedLocationUsesThePhoneAndDoesNotAsk() = runBlocking {
        var seenLat = 0.0
        var seenLon = 0.0
        val client = WeatherClient { lat, lon ->
            seenLat = lat
            seenLon = lon
            WeatherReading(31, 36, 24, 1)
        }
        val fixes = FakeFix(
            permitted = true,
            shouldAsk = false,
            point = GeoPoint(24.8, 46.7),
        )
        val skill = WeatherSkill(client, fixes) { }
        val result = skill.run(skill.match("what's the weather now")!!)
        assertEquals(24.8, seenLat, 0.001)
        assertEquals(46.7, seenLon, 0.001)
        assertTrue(result.text.startsWith("Here, it's 31 degrees"))
        assertEquals("Here 31°", result.shelf)
        assertNull(result.needsPermission)
        assertFalse(fixes.asked)
    }

    @Test
    fun arabicReplyUsesRiyadhWhenThereIsNoFix() = runBlocking {
        val skill = skill(permitted = false, ask = false)
        val result = skill.run(skill.match("كيف الجو")!!)
        assertTrue(result.text.startsWith("في الرياض"))
        assertTrue(result.text.contains("28"))
        assertNull(result.needsPermission)
    }

    @Test
    fun parsesOpenMeteoJson() {
        val body = """
            {"current":{"temperature_2m":28.4,"weather_code":1},
             "daily":{"temperature_2m_max":[34.2],"temperature_2m_min":[21.6]}}
        """.trimIndent()
        val reading = OpenMeteo.parse(body)
        assertEquals(28, reading?.celsius)
        assertEquals(34, reading?.high)
        assertEquals(22, reading?.low)
        assertEquals(1, reading?.code)
        assertTrue(OpenMeteo.url(24.7136, 46.6753).contains("latitude=24.7136"))
        assertTrue(OpenMeteo.url(24.7136, 46.6753).startsWith("https://api.open-meteo.com/v1/forecast?"))
    }

    private fun skill(permitted: Boolean, ask: Boolean) = WeatherSkill(
        fixedClient(),
        FakeFix(permitted, ask),
    ) { }

    private fun fixedClient() = WeatherClient { _, _ -> WeatherReading(28, 34, 22, 0) }

    private class FakeFix(
        override val permitted: Boolean,
        override var shouldAsk: Boolean,
        private val point: GeoPoint? = null,
    ) : CoarseFix {
        var asked = false
        override fun markAsked() {
            asked = true
        }
        override suspend fun read(): GeoPoint? = point
    }
}
