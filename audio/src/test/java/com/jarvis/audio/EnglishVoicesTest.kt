package com.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishVoicesTest {
    @Test
    fun prefersOfflineBritishMaleIds() {
        val picked = EnglishVoices.pick(
            listOf(
                voice("en-gb-x-fis-local", "GB", features = setOf("female")),
                voice("en-gb-x-gbd-local", "GB"),
                voice("en-gb-x-rjs-local", "GB"),
                voice("en-gb-x-rjs-network", "GB", network = true),
                voice("en-us-x-iol-local", "US"),
            ),
        )
        assertEquals("en-gb-x-rjs-local", picked?.name)
    }

    @Test
    fun gbdBeatsAGenericBritishVoice() {
        val picked = EnglishVoices.pick(
            listOf(
                voice("en-gb-x-fis-local", "GB"),
                voice("en-gb-x-gbd-local", "GB"),
            ),
        )
        assertEquals("en-gb-x-gbd-local", picked?.name)
    }

    @Test
    fun maleFeatureBeatsFemaleWhenNoKnownId() {
        val picked = EnglishVoices.pick(
            listOf(
                voice("en-gb-x-fis-local", "GB", features = setOf("female")),
                voice("en-gb-language", "GB", features = setOf("male")),
            ),
        )
        assertEquals("en-gb-language", picked?.name)
    }

    @Test
    fun femaleIsNotTreatedAsMale() {
        val picked = EnglishVoices.pick(
            listOf(
                voice("en-gb-x-fis-local", "GB", features = setOf("female")),
                voice("en-gb-standard", "GB"),
            ),
        )
        assertEquals("en-gb-standard", picked?.name)
        assertTrue(!EnglishVoices.isMale(voice("en-gb-x-fis-local", "GB", features = setOf("female"))))
    }

    @Test
    fun anyOfflineBritishVoiceWhenNoneAreMale() {
        val picked = EnglishVoices.pick(
            listOf(
                voice("en-gb-x-fis-local", "GB"),
                voice("en-us-x-iol-local", "US"),
            ),
        )
        assertEquals("en-gb-x-fis-local", picked?.name)
    }

    @Test
    fun networkBritishVoicesFallBackToOfflineAmericanMale() {
        val picked = EnglishVoices.pick(
            listOf(
                voice("en-gb-x-rjs-network", "GB", network = true),
                voice("en-us-x-sfg-local", "US", features = setOf("female")),
                voice("en-us-x-iol-local", "US"),
            ),
        )
        assertEquals("en-us-x-iol-local", picked?.name)
    }

    @Test
    fun noEnglishVoiceReturnsNull() {
        assertNull(EnglishVoices.pick(listOf(voice("ar-xa-x-local", "SA", language = "ar"))))
    }

    @Test
    fun listsEveryBritishNameIncludingNetwork() {
        val names = EnglishVoices.enGbNames(
            listOf(
                voice("en-gb-x-rjs-network", "GB", network = true),
                voice("en-gb-x-fis-local", "GB"),
                voice("en-us-x-iol-local", "US"),
            ),
        )
        assertEquals(listOf("en-gb-x-fis-local", "en-gb-x-rjs-network"), names)
    }

    @Test
    fun samsungMaleBeatsSamsungFemaleAndDefaults() {
        val male = ListedVoice("en-US-SMTd01", "eng", "USA", false)
        val picked = EnglishVoices.pick(
            listOf(
                ListedVoice("en-US-SMTf00", "eng", "USA", false),
                ListedVoice("en-US-default", "eng", "USA", false),
                male,
            ),
        )
        assertEquals("en-US-SMTd01", picked?.name)
        assertTrue(EnglishVoices.isMale(male))
        assertTrue(!EnglishVoices.isMale(ListedVoice("en-US-SMTf00", "eng", "USA", false)))
    }

    @Test
    fun britishSamsungBeatsAmericanMale() {
        val picked = EnglishVoices.pick(
            listOf(
                ListedVoice("en-US-SMTd01", "eng", "USA", false),
                ListedVoice("en-GB-SMTd01", "eng", "GBR", false),
            ),
        )
        assertEquals("en-GB-SMTd01", picked?.name)
        assertTrue(EnglishVoices.isBritish(picked!!))
    }

    @Test
    fun pitchAndRateMatchTheJarvisEnglishSettings() {
        assertEquals(0.9f, EnglishVoices.PITCH, 0f)
        assertEquals(1.0f, EnglishVoices.RATE, 0f)
    }

    private fun voice(
        name: String,
        country: String,
        language: String = "en",
        network: Boolean = false,
        features: Set<String> = emptySet(),
    ) = ListedVoice(name, language, country, network, features)
}
