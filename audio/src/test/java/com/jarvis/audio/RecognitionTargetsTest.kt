package com.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionTargetsTest {
    private val tts = RecognitionTarget(
        GOOGLE_TTS_PACKAGE,
        "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
    )
    private val googleApp = RecognitionTarget(
        GOOGLE_APP_PACKAGE,
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
    )
    private val bixby = RecognitionTarget(
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.agent.mainui.voiceinteraction.RecognitionServiceTrampoline",
    )

    @Test
    fun nothingVisibleMeansNoRecognizer() {
        assertNull(pickRecognitionTarget(emptyList(), tts))
        assertNull(chooseRecognizer("auto", "en-US", emptyList(), null).target)
        assertNull(chooseRecognizer("ar", "en-US", emptyList(), selected = tts).target)
    }

    @Test
    fun selectedServiceWinsWhenItIsVisible() {
        assertEquals(tts, pickRecognitionTarget(listOf(bixby, googleApp, tts), tts))
    }

    @Test
    fun hiddenSelectionFallsThroughToSpeechServicesThenTheGoogleApp() {
        assertEquals(tts, pickRecognitionTarget(listOf(bixby, googleApp, tts), bixby.copy(className = "missing")))
        assertEquals(googleApp, pickRecognitionTarget(listOf(bixby, googleApp), null))
        assertEquals(bixby, pickRecognitionTarget(listOf(bixby), null))
    }

    @Test
    fun arabicPrefersTheOnlineGoogleAppRecognizer() {
        val choice = chooseRecognizer("ar", "en-US", listOf(tts, googleApp), null)
        assertEquals(googleApp, choice.target)
        assertEquals("ar-SA", choice.primaryTag)
        assertEquals(listOf("en-US"), choice.extraTags)
        assertEquals("ar-SA", choice.preferenceTag)
        assertEquals(false, choice.preferOffline)
        assertEquals("ar", choice.setting)
    }

    @Test
    fun autoKeepsTheDeviceTagOnTheOnlineRecognizer() {
        val choice = chooseRecognizer("auto", "en-US", listOf(tts, googleApp), null)
        assertEquals(googleApp, choice.target)
        assertEquals("en-US", choice.primaryTag)
        assertEquals(listOf("ar-SA"), choice.extraTags)
        assertNull(choice.preferenceTag)
        assertEquals(false, choice.preferOffline)
    }

    @Test
    fun englishKeepsTheOfflineGoogleTtsBehaviour() {
        val choice = chooseRecognizer("en", "en-US", listOf(tts, googleApp), tts)
        assertEquals(tts, choice.target)
        assertEquals("en-US", choice.primaryTag)
        assertEquals(emptyList<String>(), choice.extraTags)
        assertNull(choice.preferenceTag)
        assertEquals(true, choice.preferOffline)
    }

    @Test
    fun withoutTheGoogleAppRecognizerArabicFallsBackToTheOfflineChoice() {
        val ar = chooseRecognizer("ar", "en-US", listOf(tts, bixby), selected = bixby)
        assertEquals(bixby, ar.target)
        assertEquals("ar-SA", ar.primaryTag)
        assertEquals(listOf("en-US"), ar.extraTags)
        assertEquals(true, ar.preferOffline)
        assertNull(ar.preferenceTag)

        val auto = chooseRecognizer("auto", "en-GB", listOf(tts, bixby), null)
        assertEquals(tts, auto.target)
        assertEquals("en-GB", auto.primaryTag)
        assertEquals(listOf("ar-SA"), auto.extraTags)
        assertEquals(true, auto.preferOffline)
    }

    @Test
    fun forcedOfflineSkipsTheOnlinePreference() {
        val choice = chooseRecognizer("ar", "en-US", listOf(tts, googleApp), null, forceOffline = true)
        assertEquals(tts, choice.target)
        assertEquals("ar-SA", choice.primaryTag)
        assertEquals(listOf("en-US"), choice.extraTags)
        assertEquals(true, choice.preferOffline)
    }

    @Test
    fun targetNameIsThePackageOrDefault() {
        assertEquals(GOOGLE_APP_PACKAGE, chooseRecognizer("ar", "en-US", listOf(googleApp), null).targetName)
        assertEquals("default", chooseRecognizer("ar", "en-US", emptyList(), null).targetName)
    }
}
