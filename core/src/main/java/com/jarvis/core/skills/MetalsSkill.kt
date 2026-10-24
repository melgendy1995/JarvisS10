package com.jarvis.core.skills

import com.jarvis.core.JarvisLog
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

data class SpotTick(
    val price: Double,
    val asOf: String,
    val source: String,
)

/** Keyless spot quotes. Null means the feed did not answer. */
interface SpotFeed {
    suspend fun metal(symbol: String): SpotTick?
    suspend fun usdToSar(): SpotTick?
}

/**
 * Gold, silver, and the dollar in riyals.
 * Gold and silver are riyals per gram unless the question asks for dollars or ounces.
 * A failed feed returns [SkillResult.continueToBrain] so search can answer.
 */
class MetalsSkill(
    private val feed: SpotFeed,
    private val log: (String) -> Unit = { line -> JarvisLog.i(TAG, line) },
) : Skill {
    override val id: String = "metals"

    override fun match(text: String): SkillCall? {
        val normalized = Utterances.normalize(text)
        if (normalized.isEmpty()) return null
        val kind = when {
            isGold(normalized) -> "gold"
            isSilver(normalized) -> "silver"
            isFx(normalized) -> "fx"
            else -> return null
        }
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        return SkillCall(
            skillId = id,
            utterance = text,
            slots = mapOf(
                "kind" to kind,
                "lang" to if (arabic) "ar" else "en",
                "usd" to if (wantsUsd(normalized) && kind != "fx") "1" else "0",
            ),
        )
    }

    override fun geminiDeclaration(): Any = mapOf(
        "name" to id,
        "description" to "Gold and silver prices, and the US dollar to Saudi riyal rate. Gold and silver are quoted in riyals per gram.",
        "parameters" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "kind" to mapOf(
                    "type" to "string",
                    "description" to "gold, silver, or fx",
                ),
            ),
        ),
    )

    override fun groqTool(): Any = mapOf(
        "type" to "function",
        "function" to geminiDeclaration(),
    )

    override suspend fun run(call: SkillCall): SkillResult {
        val kind = call.slots["kind"] ?: kindOf(Utterances.normalize(call.utterance)) ?: "gold"
        val arabic = call.slots["lang"] == "ar" || call.utterance.any { it in '\u0600'..'\u06FF' }
        val usd = call.slots["usd"] == "1" || wantsUsd(Utterances.normalize(call.utterance))
        return try {
            when (kind) {
                "fx" -> fx(arabic)
                "silver" -> metal("XAG", arabic, usd && kind != "fx")
                else -> metal("XAU", arabic, usd)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("quote failed ${error.javaClass.simpleName}")
            defer()
        }
    }

    private suspend fun metal(symbol: String, arabic: Boolean, usd: Boolean): SkillResult {
        val tick = feed.metal(symbol) ?: return defer()
        val name = if (symbol == "XAG") {
            if (arabic) "الفضة" else "Silver"
        } else {
            if (arabic) "الذهب" else "Gold"
        }
        val day = MetalsQuote.speakDate(tick.asOf, arabic)
        if (usd) {
            val amount = MetalsQuote.speakAmount(tick.price)
            val text = if (arabic) {
                "$name $amount دولار للأونصة، بتاريخ $day، من ${tick.source}."
            } else {
                "$name is $amount dollars an ounce as of $day, from ${tick.source}."
            }
            log("path=metals metal=$symbol unit=usd/oz source=${tick.source}")
            return SkillResult(text = text, shelf = "$name $amount USD/oz")
        }
        val fx = feed.usdToSar() ?: return defer()
        val perGram = MetalsQuote.sarPerGram(tick.price, fx.price)
        val amount = MetalsQuote.speakAmount(perGram)
        val text = if (arabic) {
            "$name $amount ريال للجرام، بتاريخ $day، من ${tick.source}."
        } else {
            "$name is $amount riyals a gram as of $day, from ${tick.source}."
        }
        log("path=metals metal=$symbol unit=sar/g source=${tick.source}")
        val shelfName = if (symbol == "XAG") "Silver" else "Gold"
        return SkillResult(text = text, shelf = "$shelfName $amount SAR/g")
    }

    private suspend fun fx(arabic: Boolean): SkillResult {
        val tick = feed.usdToSar() ?: return defer()
        val amount = MetalsQuote.speakAmount(tick.price)
        val day = MetalsQuote.speakDate(tick.asOf, arabic)
        val text = if (arabic) {
            "الدولار بـ $amount ريال، بتاريخ $day، من ${tick.source}."
        } else {
            "One dollar is $amount riyals, as of $day, from ${tick.source}."
        }
        log("path=metals metal=fx unit=sar source=${tick.source}")
        return SkillResult(text = text, shelf = "USD $amount SAR")
    }

    private fun defer(): SkillResult {
        log("path=metals defer=search")
        return SkillResult(text = "", success = false, continueToBrain = true)
    }

    private fun kindOf(normalized: String): String? = when {
        isGold(normalized) -> "gold"
        isSilver(normalized) -> "silver"
        isFx(normalized) -> "fx"
        else -> null
    }

    companion object {
        const val TAG = "JarvisSearch"

        private fun isGold(text: String): Boolean {
            val metal = text.contains("ذهب") || Regex("""\b(gold|xau)\b""").containsMatchIn(text)
            return metal && priceish(text)
        }

        private fun isSilver(text: String): Boolean {
            val metal = text.contains("فضه") || Regex("""\b(silver|xag)\b""").containsMatchIn(text)
            return metal && priceish(text)
        }

        private fun isFx(text: String): Boolean {
            if (text.contains("usd") && text.contains("sar")) return true
            if (text.contains("exchange rate") || text.contains("سعر الصرف")) return true
            val dollar = text.contains("dollar") || text.contains("دولار")
            val riyal = text.contains("riyal") || text.contains("ريال")
            if (dollar && riyal) return true
            val ask = text.contains("price") || text.contains("rate") || text.contains("سعر") || text.contains("كم")
            if (dollar && ask && !text.contains("ذهب") && !Regex("""\b(gold|silver)\b""").containsMatchIn(text)) {
                return true
            }
            return false
        }

        private fun priceish(text: String): Boolean {
            return text.contains("سعر") || text.contains("بكم") || text.contains("كم") ||
                Regex("""\b(price|prices|cost|worth|ounce|gram|grams|rate)\b""").containsMatchIn(text)
        }

        private fun wantsUsd(text: String): Boolean {
            if (text.contains("ريال") || text.contains("جرام") || Regex("""\b(sar|riyal|riyals|gram|grams)\b""").containsMatchIn(text)) {
                return false
            }
            return text.contains("دولار") || text.contains("اونص") || text.contains("اوقيه") ||
                Regex("""\b(dollar|dollars|usd|ounce|ounces)\b""").containsMatchIn(text)
        }
    }
}

/** Parsers and the troy-ounce conversion. The phone does not hard-code a price. */
object MetalsQuote {
    const val GRAMS_PER_TROY_OUNCE = 31.1034768
    const val GOLD_SOURCE = "Gold API"
    const val FX_SOURCE = "ExchangeRate-API"

    fun sarPerGram(usdPerOunce: Double, sarPerUsd: Double): Double {
        return usdPerOunce / GRAMS_PER_TROY_OUNCE * sarPerUsd
    }

    fun speakAmount(value: Double): String {
        return if (value >= 50.0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    fun parseGoldApi(body: String, symbol: String): SpotTick? {
        if (!body.contains(""""symbol":"$symbol"""") && !body.contains(""""symbol": "$symbol"""")) {
            return null
        }
        val price = number(body, "price") ?: return null
        if (price <= 0.0) return null
        return SpotTick(price = price, asOf = string(body, "updatedAt").orEmpty(), source = GOLD_SOURCE)
    }

    fun parseUsdSar(body: String): SpotTick? {
        val rate = number(body, "SAR") ?: return null
        if (rate <= 0.0) return null
        return SpotTick(price = rate, asOf = string(body, "time_last_update_utc").orEmpty(), source = FX_SOURCE)
    }

    fun speakDate(raw: String, arabic: Boolean): String {
        val instant = parseInstant(raw) ?: return if (arabic) "اليوم" else "today"
        val date = instant.atZone(RIYADH).toLocalDate()
        val month = if (arabic) MONTHS_AR[date.monthValue - 1] else MONTHS_EN[date.monthValue - 1]
        return "${date.dayOfMonth} $month ${date.year}"
    }

    private fun parseInstant(raw: String): Instant? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { return Instant.parse(text) }
        runCatching { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        return null
    }

    private fun number(body: String, key: String): Double? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+(?:\.\d+)?)""").find(body) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun string(body: String, key: String): String? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]*)"""").find(body) ?: return null
        return match.groupValues[1]
    }

    private val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")
    private val MONTHS_EN = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val MONTHS_AR = listOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
    )
}
