package com.jarvis.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class JarvisSpeaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech
    private var ready = false
    private var failed = false
    private var pending: Pending? = null
    private var onDone: (() -> Unit)? = null
    private var activeUtterance: String? = null
    private var activeBrain: String = ""

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts.setSpeechRate(1.02f)
                    tts.language = Locale.US
                    pending?.let { item ->
                        pending = null
                        start(item.text, item.brainId, item.onDone)
                    }
                } else {
                    failed = true
                    pending?.onDone?.invoke()
                    pending = null
                }
            }
        }
    }

    fun speak(text: String, brainId: String, onDone: () -> Unit) {
        main.post {
            when {
                failed -> {
                    Log.w(TAG, "tts error brain=$brainId")
                    onDone()
                }
                !ready -> {
                    pending?.onDone?.invoke()
                    pending = Pending(text, brainId, onDone)
                }
                else -> start(text, brainId, onDone)
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
            pending?.onDone?.invoke()
            pending = null
            runCatching { tts.shutdown() }
        }
    }

    private fun start(text: String, brainId: String, done: () -> Unit) {
        deliverDone()
        onDone = done
        activeBrain = brainId
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        tts.language = if (arabic) Locale.forLanguageTag("ar") else Locale.US
        val utteranceId = "jarvis-${System.nanoTime()}"
        activeUtterance = utteranceId
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "tts start brain=$brainId utterance=$utteranceId")
            }

            override fun onDone(utteranceId: String?) = deliverDone()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "tts error brain=$brainId utterance=$utteranceId")
                deliverDone()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "tts error brain=$brainId utterance=$utteranceId code=$errorCode")
                deliverDone()
            }
        })
        val spoken = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (spoken == TextToSpeech.ERROR) {
            Log.w(TAG, "tts error brain=$brainId utterance=$utteranceId")
            deliverDone()
        }
    }

    private fun deliverDone() {
        val callback = onDone ?: return
        val utteranceId = activeUtterance
        val brainId = activeBrain
        activeUtterance = null
        onDone = null
        if (utteranceId != null) Log.i(TAG, "tts done brain=$brainId utterance=$utteranceId")
        main.post { callback() }
    }

    private data class Pending(val text: String, val brainId: String, val onDone: () -> Unit)

    private companion object {
        const val TAG = "JarvisVoice"
    }
}
