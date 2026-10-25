package com.jarvis.app.system

import com.jarvis.core.skills.WeatherReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shelf weather label format and refresh scheduling. */
class ShelfWeatherTest {
    private val riyadh = WeatherReading(celsius = 31, high = 41, low = 27, code = 0)

    @Test
    fun labelMatchesTheWeatherSkillShelfFormat() {
        assertEquals("Riyadh 31°", ShelfWeather.label(deviceFix = false, reading = riyadh))
        assertEquals("Here 31°", ShelfWeather.label(deviceFix = true, reading = riyadh))
    }

    @Test
    fun noReadingYieldsNoLabel() {
        assertNull(ShelfWeather.label(deviceFix = false, reading = null))
    }

    @Test
    fun aFailedFetchRetriesSoonerThanARefresh() {
        assertEquals(ShelfWeather.RETRY_MILLIS, ShelfWeather.nextDelay(ok = false))
        assertEquals(ShelfWeather.REFRESH_MILLIS, ShelfWeather.nextDelay(ok = true))
    }

    @Test
    fun refreshIsHalfAnHourAndRetryIsTwoMinutes() {
        assertEquals(30 * 60 * 1000L, ShelfWeather.REFRESH_MILLIS)
        assertEquals(2 * 60 * 1000L, ShelfWeather.RETRY_MILLIS)
    }
}
