package com.jarvis.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Hold-to-talk wrapper around [SpeechRecognizer]. [RecognizerIntent.EXTRA_PREFER_OFFLINE] asks
 * for the on-device recognizer when the device has one. Package visibility for
 * RecognitionService is declared by the app manifest.
 */
class PushToTalkRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null
    private var session = 0
    var holding: Boolean = false
        private set

    class Callbacks(
        val onLevel: (Float) -> Unit,
        val onPartial: (String) -> Unit,
        val onFinal: (String) -> Unit,
        val onError: (message: String, hard: Boolean) -> Unit,
    )

    fun start(callbacks: Callbacks) {
        main.post {
            this.callbacks = callbacks
            holding = true
            begin()
        }
    }

    fun stop() {
        main.post {
            holding = false
            val current = recognizer
            if (current == null) {
                callbacks?.onFinal("")
            } else {
                runCatching { current.stopListening() }
            }
        }
    }

    fun release() {
        main.post {
            holding = false
            callbacks = null
            retire()
        }
    }

    private fun begin() {
        retire()
        if (!holding) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            callbacks?.onError("Speech recognition is not available on this phone.", true)
            return
        }
        val generation = session
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
        if (created == null) {
            callbacks?.onError("Speech recognition is not available on this phone.", true)
            return
        }
        recognizer = created
        created.setRecognitionListener(listener(generation))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        val started = runCatching { created.startListening(intent) }.isSuccess
        if (!started) {
            callbacks?.onError("Speech recognition is not available on this phone.", true)
            retire()
        }
    }

    private fun retire() {
        val current = recognizer
        recognizer = null
        session++
        if (current != null) {
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
    }

    private fun listener(generation: Int): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (generation != session) return
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            callbacks?.onLevel(level)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (generation != session) return
            val text = partialResults.bestHypothesis()
            if (text.isNotEmpty()) callbacks?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (generation != session) return
            val text = results.bestHypothesis()
            main.post {
                if (generation != session) return@post
                callbacks?.onFinal(text)
                retire()
            }
        }

        override fun onError(error: Int) {
            if (generation != session) return
            main.post {
                if (generation != session) return@post
                val soft = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (holding && soft) {
                    begin()
                    return@post
                }
                val hard = !soft && error != SpeechRecognizer.ERROR_CLIENT
                callbacks?.onError(errorMessage(error), hard)
                retire()
            }
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone had an audio error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition is not available on this phone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "On-device recognition is unavailable right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition failed."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
        else -> "I didn't catch that."
    }
}

private fun Bundle?.bestHypothesis(): String {
    return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}
