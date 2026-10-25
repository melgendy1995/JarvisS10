package com.jarvis.app.system

import com.jarvis.core.skills.WeatherReading
import com.jarvis.core.skills.WeatherSkill

/**
 * Shelf weather refresh scheduling and label formatting. Pure so unit tests can
 * drive it; the ViewModel only runs the fetch loop around it.
 */
internal object ShelfWeather {
    /** Successful fetches come back every 30 minutes. */
    const val REFRESH_MILLIS = 30 * 60 * 1000L

    /** A failed fetch retries after 2 minutes and keeps the last label. */
    const val RETRY_MILLIS = 2 * 60 * 1000L

    const val TAG = "JarvisWeather"
    const val RIYADH_LAT = WeatherSkill.RIYADH_LAT
    const val RIYADH_LON = WeatherSkill.RIYADH_LON

    /** Same format as the WeatherSkill shelf, e.g. "Riyadh 31°" or "Here 31°". */
    fun label(deviceFix: Boolean, reading: WeatherReading?): String? {
        reading ?: return null
        return "${if (deviceFix) "Here" else "Riyadh"} ${reading.celsius}°"
    }

    fun nextDelay(ok: Boolean): Long = if (ok) REFRESH_MILLIS else RETRY_MILLIS
}
