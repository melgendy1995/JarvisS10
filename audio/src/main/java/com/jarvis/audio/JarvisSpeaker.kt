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
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var probe: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var closed = false
    private var pending: Pending? = null
    private var onDone: (() -> Unit)? = null
    private var activeUtterance: String? = null
    private var activeBrain: String = ""

    init {
        val initial = TextToSpeech(appContext) { status ->
            main.post { onInitial(status) }
        }
        tts = initial
    }

    private fun onInitial(status: Int) {
        if (closed) return
        val initial = tts
        if (status != TextToSpeech.SUCCESS || initial == null) {
            failed = true
            pending?.onDone?.invoke()
            pending = null
            return
        }
        val googleInstalled = initial.engines.orEmpty().any { it.name == GOOGLE_TTS }
        if (!JarvisTts.hasOfflineBritish(initial) && googleInstalled) {
            Log.i(JarvisTts.TAG, "probing $GOOGLE_TTS for an offline en-GB voice")
            probe = TextToSpeech(appContext, { probeStatus ->
                main.post { onProbe(initial, probeStatus) }
            }, GOOGLE_TTS)
            return
        }
        becomeReady(initial)
    }

    private fun onProbe(initial: TextToSpeech, status: Int) {
        val probed = probe
        probe = null
        if (closed) {
            runCatching { initial.shutdown() }
            runCatching { probed?.shutdown() }
            return
        }
        if (status == TextToSpeech.SUCCESS && probed != null && JarvisTts.hasOfflineBritish(probed)) {
            Log.i(JarvisTts.TAG, "selected engine=$GOOGLE_TTS")
            runCatching { initial.shutdown() }
            becomeReady(probed)
            return
        }
        val availability = probed?.isLanguageAvailable(Locale.UK)
        val missing = availability == TextToSpeech.LANG_MISSING_DATA
        val names = probed?.let { EnglishVoices.enGbNames(JarvisTts.snapshot(it)) }.orEmpty()
        Log.i(
            JarvisTts.TAG,
            "google en-GB voices=$names missingData=$missing availability=$availability",
        )
        runCatching { probed?.shutdown() }
        Log.i(JarvisTts.TAG, "selected engine=${initial.defaultEngine}")
        becomeReady(initial)
    }

    private fun becomeReady(engine: TextToSpeech) {
        tts = engine
        ready = true
        // Idle default stays the previous rate until an English line applies JarvisTts.
        engine.setPitch(1.0f)
        engine.setSpeechRate(1.02f)
        engine.language = Locale.US
        pending?.let { item ->
            pending = null
            start(item.text, item.brainId, item.onDone)
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
            runCatching { tts?.stop() }
            deliverDone()
        }
    }

    fun shutdown() {
        main.post {
            closed = true
            deliverDone()
            pending?.onDone?.invoke()
            pending = null
            runCatching { tts?.shutdown() }
            runCatching { probe?.shutdown() }
            probe = null
        }
    }

    private fun start(text: String, brainId: String, done: () -> Unit) {
        val tts = tts ?: return done()
        deliverDone()
        onDone = done
        activeBrain = brainId
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        if (arabic) {
            tts.language = Locale.forLanguageTag("ar")
            tts.setPitch(1.0f)
            tts.setSpeechRate(1.02f)
        } else {
            JarvisTts.applyEnglish(tts)
        }
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
        const val GOOGLE_TTS = "com.google.android.tts"
    }
}
