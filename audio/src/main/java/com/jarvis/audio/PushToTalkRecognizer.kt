package com.jarvis.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Hold-to-talk around [SpeechRecognizer].
 *
 * Every create, start, stop, and destroy runs on the main looper. The recognizer is
 * destroyed with [SpeechRecognizer.destroy] only — calling [SpeechRecognizer.cancel]
 * first posts a message that then runs after the service is cleared and logs
 * "not connected to the recognition service". A new listen is never started on an
 * instance that has been destroyed. When no recognition service is visible, the
 * caller records the hold and sends it to Whisper.
 */
class PushToTalkRecognizer(
    context: Context,
    /** "auto", "en", or "ar". Auto follows the phone language with an Arabic re-check. */
    private val voiceLanguage: () -> String = { "auto" },
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null
    private var session = 0
    private var ready = false
    private var finished = false
    private var unavailable = false
    private var clientRetries = 0
    private var beginning = false
    private var once = false
    private var keepAlive = false
    /** True while the current session binds the online Google app recognizer. */
    private var onlineSession = false
    /** Set after the online recognizer failed, so the one retry uses the offline target. */
    private var forcedOffline = false
    private var onlineRetries = 0
    var holding: Boolean = false
        private set

    class Callbacks(
        val onLevel: (Float) -> Unit,
        val onPartial: (String) -> Unit,
        val onFinal: (text: String, confidence: Float) -> Unit,
        val onError: (message: String, hard: Boolean) -> Unit,
        val onFallbackCapture: () -> Unit = {},
        val onRmsDb: (Float) -> Unit = {},
    )

    fun start(callbacks: Callbacks) {
        main.post {
            this.callbacks = callbacks
            holding = true
            once = false
            keepAlive = false
            finished = false
            unavailable = false
            clientRetries = 0
            onlineRetries = 0
            forcedOffline = false
            begin()
        }
    }

    /**
     * One utterance with the finger up. [keepAlive] reports a soft timeout through
     * [Callbacks.onError] so the caller can restart, instead of treating it as an empty result.
     * The wake-word recorder must already be released.
     */
    fun listenOnce(callbacks: Callbacks, keepAlive: Boolean = false) {
        main.post {
            this.callbacks = callbacks
            holding = false
            once = true
            this.keepAlive = keepAlive
            finished = false
            unavailable = false
            clientRetries = 0
            onlineRetries = 0
            forcedOffline = false
            begin()
        }
    }

    fun stop() {
        main.post {
            holding = false
            if (unavailable) {
                unavailable = false
                deliverError("Speech recognition is not available on this phone.", hard = false)
                return@post
            }
            val current = recognizer
            if (current == null) {
                deliverFinal("")
                return@post
            }
            // Queued until the service connects. Do not cancel or destroy here.
            runCatching { current.stopListening() }
        }
    }

    /** End a [listenOnce] session. A newer hold is left running. */
    fun stopOnce() {
        main.post {
            if (!once || holding) return@post
            if (unavailable) {
                unavailable = false
                once = false
                deliverFinal("")
                return@post
            }
            val current = recognizer
            if (current == null) {
                deliverFinal("")
                return@post
            }
            runCatching { current.stopListening() }
        }
    }

    /** Drop the session without an empty final. Does not change [holding]. */
    fun discard() {
        main.post {
            once = false
            keepAlive = false
            finished = true
            callbacks = null
            unavailable = false
            destroyRecognizer()
        }
    }

    fun release() {
        main.post {
            holding = false
            once = false
            keepAlive = false
            callbacks = null
            finished = true
            unavailable = false
            destroyRecognizer()
        }
    }

    private fun armed(): Boolean = !finished && (holding || once)

    private fun begin() {
        if (beginning) return
        beginning = true
        try {
            destroyRecognizer()
            if (!armed()) return
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                beginFallback()
                return
            }
            val choice = chooseRecognizer(
                voiceLanguage(),
                Locale.getDefault().toLanguageTag(),
                visibleTargets(),
                selectedTarget(),
                forceOffline = forcedOffline,
            )
            onlineSession = !forcedOffline && choice.target?.packageName == GOOGLE_APP_PACKAGE
            val created = runCatching {
                if (choice.target != null) {
                    SpeechRecognizer.createSpeechRecognizer(
                        appContext,
                        ComponentName(choice.target.packageName, choice.target.className),
                    )
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
            }.getOrNull()
            if (created == null) {
                beginFallback()
                return
            }
            val generation = session
            ready = false
            recognizer = created
            created.setRecognitionListener(listener(generation))
            if (recognizer !== created || generation != session || !armed()) {
                destroyRecognizer()
                return
            }
            val started = runCatching { created.startListening(listenIntent(choice)) }.isSuccess
            if (!started || recognizer !== created) {
                destroyRecognizer()
                beginFallback()
                return
            }
            Log.i(
                LOG_TAG,
                "stt listen target=${choice.targetName} lang=${choice.primaryTag} " +
                    "extra=${choice.extraTags.joinToString("|")} offline=${choice.preferOffline} " +
                    "setting=${choice.setting}",
            )
        } finally {
            beginning = false
        }
    }

    private fun beginFallback() {
        unavailable = true
        callbacks?.onFallbackCapture()
    }

    private fun destroyRecognizer() {
        val current = recognizer
        recognizer = null
        ready = false
        session++
        if (current != null) {
            runCatching { current.destroy() }
        }
    }

    private fun deliverFinal(text: String, confidence: Float = -1f) {
        if (finished) return
        finished = true
        callbacks?.onFinal(text, confidence)
    }

    private fun deliverError(message: String, hard: Boolean) {
        if (finished) return
        finished = true
        callbacks?.onError(message, hard)
    }

    private fun listenIntent(choice: RecognizerChoice): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, choice.preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, choice.primaryTag)
            if (choice.extraTags.isNotEmpty()) {
                putExtra(ADDITIONAL_LANGUAGES, choice.extraTags.toTypedArray())
            }
            choice.preferenceTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it) }
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            if (once) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            }
        }
    }

    private fun visibleTargets(): List<RecognitionTarget> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        return appContext.packageManager.queryIntentServices(intent, 0).mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            RecognitionTarget(service.packageName, service.name)
        }
    }

    private fun selectedTarget(): RecognitionTarget? {
        val flat = Settings.Secure.getString(
            appContext.contentResolver,
            "voice_recognition_service",
        ) ?: return null
        val component = ComponentName.unflattenFromString(flat) ?: return null
        return RecognitionTarget(component.packageName, component.className)
    }

    private fun listener(generation: Int): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (generation != session) return
            ready = true
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (generation != session) return
            callbacks?.onRmsDb(rmsdB)
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
            val confidence = results
                ?.getFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)
                ?.firstOrNull()
                ?: -1f
            main.post {
                if (generation != session || finished) return@post
                deliverFinal(text, confidence)
                destroyRecognizer()
            }
        }

        override fun onError(error: Int) {
            if (generation != session) return
            main.post {
                if (generation != session || finished) return@post
                Log.i(LOG_TAG, "stt error code=$error")
                if (onlineSession && onlineRetries < 1 && isOnlineFallback(error)) {
                    // The online Google app recognizer failed before results. One retry
                    // with the offline Speech Services by Google target so English still works.
                    onlineRetries++
                    forcedOffline = true
                    clientRetries = 0
                    Log.i(LOG_TAG, "stt retry offline after code=$error")
                    begin()
                    return@post
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    deliverError(errorMessage(error), hard = true)
                    destroyRecognizer()
                    return@post
                }
                if (once) {
                    if (!ready && isRetryable(error) && clientRetries < 1) {
                        clientRetries++
                        begin()
                        return@post
                    }
                    if (!ready) {
                        destroyRecognizer()
                        beginFallback()
                        return@post
                    }
                    if (keepAlive && isSoft(error)) {
                        finished = true
                        destroyRecognizer()
                        callbacks?.onError("", false)
                        return@post
                    }
                    if (isSoft(error)) {
                        deliverFinal("")
                        destroyRecognizer()
                        return@post
                    }
                    val onceHard = !isSoft(error) && error != SpeechRecognizer.ERROR_CLIENT
                    deliverError(errorMessage(error), onceHard)
                    destroyRecognizer()
                    return@post
                }
                if (holding && isSoft(error)) {
                    begin()
                    return@post
                }
                if (holding && !ready && isRetryable(error) && clientRetries < 1) {
                    clientRetries++
                    begin()
                    return@post
                }
                if (holding && !ready) {
                    destroyRecognizer()
                    beginFallback()
                    return@post
                }
                val hard = !isSoft(error) && error != SpeechRecognizer.ERROR_CLIENT
                deliverError(errorMessage(error), hard)
                destroyRecognizer()
            }
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone had an audio error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition is not available on this phone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "That language is not set up for speech on this phone."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "On-device recognition is unavailable right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition failed."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech recognition is not available on this phone."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
        else -> if (code <= 0) "" else "I didn't catch that."
    }

    /** Network or language failures of the online recognizer, before any result. */
    private fun isOnlineFallback(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
    }

    private fun isSoft(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error <= 0
    }

    private fun isRetryable(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
    }
}

private const val ADDITIONAL_LANGUAGES = "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"
private const val LOG_TAG = "JarvisVoice"

private fun Bundle?.bestHypothesis(): String {
    return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}
