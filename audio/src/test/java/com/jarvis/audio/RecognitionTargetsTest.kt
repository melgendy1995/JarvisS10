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
}
