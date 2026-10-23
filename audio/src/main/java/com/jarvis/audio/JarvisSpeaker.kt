package com.jarvis.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class JarvisSpeaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech
    private var ready = false
    private var failed = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts.setSpeechRate(1.02f)
                    tts.language = Locale.US
                    pending?.let { (text, done) ->
                        pending = null
                        start(text, done)
                    }
                } else {
                    failed = true
                    pending?.second?.invoke()
                    pending = null
                }
            }
        }
    }

    fun speak(text: String, onDone: () -> Unit) {
        main.post {
            when {
                failed -> onDone()
                !ready -> {
                    pending?.second?.invoke()
                    pending = text to onDone
                }
                else -> start(text, onDone)
            }
        }
    }

    fun stop() {
        main.post {
            runCatching { tts.stop() }
            deliverDone()
        }
    }

    fun shutdown() {
        main.post {
            deliverDone()
            pending?.second?.invoke()
            pending = null
            runCatching { tts.shutdown() }
        }
    }

    private fun start(text: String, done: () -> Unit) {
        deliverDone()
        onDone = done
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        tts.language = if (arabic) Locale.forLanguageTag("ar") else Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = deliverDone()
            override fun onError(utteranceId: String?) = deliverDone()
        })
        val spoken = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "jarvis-${System.nanoTime()}")
        if (spoken == TextToSpeech.ERROR) deliverDone()
    }

    private fun deliverDone() {
        val callback = onDone ?: return
        onDone = null
        main.post { callback() }
    }
}
