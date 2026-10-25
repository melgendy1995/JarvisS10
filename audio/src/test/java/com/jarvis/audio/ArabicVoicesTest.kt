package com.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicVoicesTest {
    private fun v(name: String, network: Boolean = false, lang: String = "ar", country: String = "") =
        ListedVoice(name, lang, country, network)

    @Test
    fun prefersLocalGoogleMaleOverFemaleAndNetwork() {
        val voices = listOf(
            v("ar-xa-x-arz-local"),
            v("ar-xa-x-arc-local"),
            v("ar-xa-x-ard-network", network = true),
            v("ar-xa-x-ard-local"),
            v("en-gb-x-rjs-local", lang = "en", country = "GB"),
        )
        assertEquals("ar-xa-x-ard-local", ArabicVoices.pick(voices)?.name)
    }

    @Test
    fun fallsBackToNetworkMaleThenAnyLocal() {
        val networkMale = listOf(v("ar-xa-x-arz-local"), v("ar-xa-x-are-network", network = true))
        assertEquals("ar-xa-x-are-network", ArabicVoices.pick(networkMale)?.name)
        val onlyFemale = listOf(v("ar-xa-x-arz-network", network = true), v("ar-xa-x-arc-local"))
        assertEquals("ar-xa-x-arc-local", ArabicVoices.pick(onlyFemale)?.name)
    }

    @Test
    fun samsungMaleCountsAndNonArabicIsIgnored() {
        assertTrue(ArabicVoices.isMale(v("ar-SA-SMTm00")))
        assertFalse(ArabicVoices.isMale(v("ar-SA-SMTf00")))
        assertNull(ArabicVoices.pick(listOf(v("en-us-x-iol-local", lang = "en", country = "US"))))
    }
}
