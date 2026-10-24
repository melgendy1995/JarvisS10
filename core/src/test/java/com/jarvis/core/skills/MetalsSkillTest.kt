package com.jarvis.core.skills

import com.jarvis.core.CommandRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetalsSkillTest {
    private val gold = """
        {"name":"Gold","price":3996.899902,"symbol":"XAU","updatedAt":"2026-10-24T08:37:22Z"}
    """.trimIndent()
    private val fx = """
        {"result":"success","time_last_update_utc":"Sat, 24 Oct 2026 00:02:31 +0000","rates":{"SAR":3.75,"EUR":0.92}}
    """.trimIndent()

    @Test
    fun sarPerGramUsesTheTroyOunceAndTheLiveRiyalRate() {
        val tick = MetalsQuote.parseGoldApi(gold, "XAU")
        val rate = MetalsQuote.parseUsdSar(fx)
        assertEquals(3996.899902, tick!!.price, 0.0001)
        assertEquals(3.75, rate!!.price, 0.0001)
        assertEquals("Gold API", tick.source)
        assertEquals("ExchangeRate-API", rate.source)
        val perGram = MetalsQuote.sarPerGram(tick.price, rate.price)
        assertEquals(481.89, perGram, 0.02)
        assertEquals("482", MetalsQuote.speakAmount(perGram))
        assertEquals("6.52", MetalsQuote.speakAmount(6.516))
        assertEquals("24 October 2026", MetalsQuote.speakDate(tick.asOf, arabic = false))
        assertEquals("24 أكتوبر 2026", MetalsQuote.speakDate(rate.asOf, arabic = true))
        assertNull(MetalsQuote.parseGoldApi(gold, "XAG"))
    }

    @Test
    fun goldPriceTodayIsRiyalsPerGramWithASource() = runBlocking {
        val skill = MetalsSkill(feed())
        val result = skill.run(skill.match("gold price today")!!)
        assertTrue(result.text.contains("482 riyals a gram"))
        assertTrue(result.text.contains("Gold API"))
        assertTrue(result.text.contains("24 October 2026"))
        assertEquals("Gold 482 SAR/g", result.shelf)
        assertFalse(result.continueToBrain)

        val arabic = skill.run(skill.match("كم سعر الذهب اليوم")!!)
        assertTrue(arabic.text.contains("482 ريال للجرام"))
        assertTrue(arabic.text.contains("أكتوبر"))
    }

    @Test
    fun dollarQuestionStaysInDollarsAndFxIsTheRiyalRate() = runBlocking {
        val skill = MetalsSkill(feed())
        val ounce = skill.run(skill.match("gold price in dollars per ounce")!!)
        assertTrue(ounce.text.contains("3997 dollars an ounce"))
        assertTrue(ounce.text.contains("Gold API"))
        val rate = skill.run(skill.match("usd/sar")!!)
        assertTrue(rate.text.contains("3.75 riyals"))
        assertTrue(rate.text.contains("ExchangeRate-API"))
    }

    @Test
    fun newsAndWeatherAreNotAQuote() {
        val skill = MetalsSkill(feed())
        assertNull(skill.match("latest news about Saudi Arabia"))
        assertNull(skill.match("what's the weather today"))
        assertNull(skill.match("who won the last Al Hilal match"))
        assertNull(skill.match("آخر أخبار السعودية"))
    }

    @Test
    fun aFailedQuoteLetsSearchAnswer() = runBlocking {
        val skill = MetalsSkill(object : SpotFeed {
            override suspend fun metal(symbol: String): SpotTick? = null
            override suspend fun usdToSar(): SpotTick? = null
        })
        val registry = com.jarvis.core.skills.SkillRegistry().apply { register(skill) }
        val routed = CommandRouter(registry).route("gold price today")
        assertTrue(CommandRouter.isMiss(routed))
    }

    private fun feed(): SpotFeed = object : SpotFeed {
        override suspend fun metal(symbol: String): SpotTick? {
            val code = if (symbol == "XAG") "XAG" else "XAU"
            val body = if (code == "XAG") {
                """{"name":"Silver","price":54.046001,"symbol":"XAG","updatedAt":"2026-10-24T08:37:22Z"}"""
            } else {
                gold
            }
            return MetalsQuote.parseGoldApi(body, code)
        }

        override suspend fun usdToSar(): SpotTick? = MetalsQuote.parseUsdSar(fx)
    }
}
