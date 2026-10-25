package com.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLanguageTagsTest {
    @Test
    fun autoFollowsThePhoneLocale() {
        assertEquals("ar-EG", VoiceLanguageTags.resolve("auto", "ar-EG"))
        assertEquals("en-GB", VoiceLanguageTags.resolve("auto", "en-GB"))
        assertEquals("de-DE", VoiceLanguageTags.resolve("auto", "de-DE"))
        assertEquals("en-US", VoiceLanguageTags.resolve("auto", "en-US"))
    }

    @Test
    fun enAndArForceStableTagsRegardlessOfLocale() {
        assertEquals("en-US", VoiceLanguageTags.resolve("en", "ar-EG"))
        assertEquals("en-US", VoiceLanguageTags.resolve("en", "en-GB"))
        assertEquals("ar-SA", VoiceLanguageTags.resolve("ar", "en-US"))
        assertEquals("ar-SA", VoiceLanguageTags.resolve("ar", "ar-EG"))
    }

    @Test
    fun unknownSettingsFallBackToThePhoneLocale() {
        assertEquals("en-GB", VoiceLanguageTags.resolve("fr", "en-GB"))
        assertEquals("en-GB", VoiceLanguageTags.resolve("", "en-GB"))
    }
}
