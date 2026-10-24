package com.jarvis.core.skills

import com.jarvis.core.JarvisLog
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class WeatherReading(
    val celsius: Int,
    val high: Int,
    val low: Int,
    val code: Int,
)

/** Coarse fix only. A denied prompt must not block the Riyadh fallback. */
interface CoarseFix {
    val permitted: Boolean

    /** True once, so the system dialog is not repeated after a denial. */
    val shouldAsk: Boolean

    fun markAsked()

    suspend fun read(): GeoPoint?
}

fun interface WeatherClient {
    suspend fun fetch(latitude: Double, longitude: Double): WeatherReading?
}

/**
 * Current conditions and today's high and low from Open-Meteo.
 * The phone's coarse fix is used when it is already granted. Otherwise Riyadh.
 */
class WeatherSkill(
    private val client: WeatherClient,
    private val fixes: CoarseFix,
    private val log: (String) -> Unit = { line -> JarvisLog.i(TAG, line) },
) : Skill {
    override val id: String = "weather"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        if (normalized.isEmpty()) return null
        val arabic = normalized.any { it in '\u0600'..'\u06FF' }
        val hit = if (arabic) {
            ARABIC.any { normalized.contains(it) }
        } else {
            ENGLISH.containsMatchIn(normalized)
        }
        if (!hit) return null
        return SkillCall(
            skillId = id,
            utterance = text,
            slots = mapOf("lang" to if (arabic) "ar" else "en"),
        )
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Get the current weather and today's high and low. Uses the phone's location, or Riyadh when location is off.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val arabic = call.slots["lang"] == "ar" ||
            call.utterance.any { it in '\u0600'..'\u06FF' }
        val ask = !fixes.permitted && fixes.shouldAsk
        if (ask) fixes.markAsked()
        val point = if (fixes.permitted) {
            try {
                fixes.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val device = point != null
        val latitude = point?.latitude ?: RIYADH_LAT
        val longitude = point?.longitude ?: RIYADH_LON
        val reading = try {
            client.fetch(latitude, longitude)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val permission = if (ask) COARSE_LOCATION_PERMISSION else null
        if (reading == null) {
            log("fetch failed source=${if (device) "device" else "riyadh"}")
            val text = if (arabic) {
                "ما قدرت أجيب الطقس الآن."
            } else {
                "I can't reach the weather service right now."
            }
            return SkillResult(text = text, success = false, needsPermission = permission)
        }
        val spoken = speak(reading, arabic, device)
        val shelf = "${if (device) "Here" else "Riyadh"} ${reading.celsius}°"
        log(
            "source=${if (device) "device" else "riyadh"} " +
                "lat=${format(latitude)} lon=${format(longitude)} " +
                "temp=${reading.celsius} high=${reading.high} low=${reading.low} code=${reading.code}",
        )
        return SkillResult(
            text = spoken,
            needsPermission = permission,
            shelf = shelf,
        )
    }

    private fun speak(reading: WeatherReading, arabic: Boolean, device: Boolean): String {
        val sky = sky(reading.code, arabic)
        return if (arabic) {
            val where = if (device) "هنا" else "في الرياض"
            "$where الحرارة ${reading.celsius} درجة $sky. أعلى درجة اليوم ${reading.high} وأقل درجة ${reading.low}."
        } else {
            val where = if (device) "Here" else "In Riyadh"
            "$where, it's ${reading.celsius} degrees $sky. Today's high is ${reading.high} and the low is ${reading.low}."
        }
    }

    private fun sky(code: Int, arabic: Boolean): String = when (code) {
        0 -> if (arabic) "والسماء صافية" else "and clear"
        1, 2 -> if (arabic) "والسماء صافية غالبا" else "and mostly clear"
        3 -> if (arabic) "والجو غائم" else "and overcast"
        45, 48 -> if (arabic) "مع ضباب" else "and foggy"
        51, 53, 55, 56, 57 -> if (arabic) "مع رذاذ" else "with drizzle"
        61, 63, 65, 66, 67 -> if (arabic) "وممطر" else "and rainy"
        71, 73, 75, 77, 85, 86 -> if (arabic) "مع ثلج" else "and snowy"
        80, 81, 82 -> if (arabic) "مع زخات مطر" else "with rain showers"
        95, 96, 99 -> if (arabic) "مع عواصف رعدية" else "with thunderstorms"
        else -> if (arabic) "وغائم جزئيا" else "and partly cloudy"
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.4f", value)

    companion object {
        const val TAG = "JarvisWeather"
        const val RIYADH_LAT = 24.7136
        const val RIYADH_LON = 46.6753
        const val COARSE_LOCATION_PERMISSION = "android.permission.ACCESS_COARSE_LOCATION"

        private val ENGLISH = Regex(
            """\b(?:weather|forecast|temperature|how hot|how cold|how warm|is it raining|is it cold|is it hot|is it warm)\b""",
        )
        private val ARABIC = listOf(
            "الطقس",
            "طقس",
            "الجو",
            "الحراره",
            "تمطر",
            "كم الحر",
        )
    }
}

/** Open-Meteo forecast JSON. No key. https://api.open-meteo.com/v1/forecast */
object OpenMeteo {
    fun url(latitude: Double, longitude: Double): String {
        val lat = String.format(java.util.Locale.US, "%.4f", latitude)
        val lon = String.format(java.util.Locale.US, "%.4f", longitude)
        return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&forecast_days=1&timezone=auto"
    }

    fun parse(body: String): WeatherReading? {
        val current = sliceObject(body, "current") ?: return null
        val daily = sliceObject(body, "daily") ?: return null
        val temp = number(current, "temperature_2m") ?: return null
        val code = number(current, "weather_code") ?: return null
        val high = arrayFirst(daily, "temperature_2m_max") ?: return null
        val low = arrayFirst(daily, "temperature_2m_min") ?: return null
        return WeatherReading(
            celsius = temp.roundToInt(),
            high = high.roundToInt(),
            low = low.roundToInt(),
            code = code.toInt(),
        )
    }

    private fun sliceObject(json: String, key: String): String? {
        val token = "\"$key\""
        val start = json.indexOf(token)
        if (start < 0) return null
        val brace = json.indexOf('{', start + token.length)
        if (brace < 0) return null
        var depth = 0
        for (index in brace until json.length) {
            when (json[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(brace, index + 1)
                }
            }
        }
        return null
    }

    private fun number(json: String, key: String): Double? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun arrayFirst(json: String, key: String): Double? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*\[\s*(-?\d+(?:\.\d+)?)""").find(json)
            ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }
}
