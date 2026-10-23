package com.jarvis.app.system

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Speaks a timer when the activity is gone. The kiosk path uses the live speaker instead. */
internal object FallbackSpeaker {
    fun speak(context: Context, text: String, onDone: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val appContext = context.applicationContext
            val lock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:timer")
            lock.acquire(20_000L)
            val finished = AtomicBoolean(false)
            lateinit var tts: TextToSpeech
            fun finish() {
                if (!finished.compareAndSet(false, true)) return
                if (lock.isHeld) runCatching { lock.release() }
                runCatching { tts.shutdown() }
                onDone()
            }
            tts = TextToSpeech(appContext) { status ->
                // onInit can run inside the constructor. Hop so [tts] is assigned first.
                main.post {
                    if (status != TextToSpeech.SUCCESS) {
                        finish()
                        return@post
                    }
                    val arabic = text.any { it in '\u0600'..'\u06FF' }
                    tts.language = if (arabic) Locale.forLanguageTag("ar") else Locale.US
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = finish()
                        override fun onError(utteranceId: String?) = finish()
                    })
                    val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "timer-done")
                    if (result == TextToSpeech.ERROR) finish()
                }
            }
        }
    }
}
