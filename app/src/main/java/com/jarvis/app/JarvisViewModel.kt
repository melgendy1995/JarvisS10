package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.audio.JarvisSpeaker
import com.jarvis.audio.PushToTalkRecognizer
import com.jarvis.audio.SpeechEnvelope
import com.jarvis.core.skills.SkillResult
import com.jarvis.orb.BrainAccent
import com.jarvis.orb.OrbState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class JarvisUiState(
    val orb: OrbState = OrbState.IDLE,
    val level: Float = 0f,
    val accent: BrainAccent = BrainAccent.PHONE,
    val you: String = "",
    val reply: String = "",
    val holding: Boolean = false,
)

sealed interface JarvisEvent {
    data class RequestPermission(val permission: String) : JarvisEvent
}

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JarvisApplication
    private val recognizer = PushToTalkRecognizer(application)
    private val speaker = JarvisSpeaker(application)
    private val speechQueue = Channel<String>(capacity = 16)
    private val _ui = MutableStateFlow(JarvisUiState())
    val ui = _ui.asStateFlow()
    private val _events = MutableSharedFlow<JarvisEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private var holding = false
    private var heard: String? = null
    private var routed = false
    private var generation = 0
    private var routeJob: Job? = null
    private var waitForResult: Job? = null
    private var pendingPermission: String? = null

    private val callbacks = PushToTalkRecognizer.Callbacks(
        onLevel = { level ->
            if (holding) _ui.update { it.copy(level = level) }
        },
        onPartial = { text ->
            if (text.isNotBlank()) _ui.update { it.copy(you = text) }
        },
        onFinal = { text ->
            heard = text
            if (!holding) routeHeard()
        },
        onError = { message, hard ->
            holding = false
            if (hard) {
                showProblem(message.ifBlank { "Speech recognition failed." })
            } else if (!routed) {
                showIdle(message)
            }
        },
    )

    init {
        viewModelScope.launch {
            app.announcements.collect { message ->
                speechQueue.send(message)
            }
        }
        viewModelScope.launch {
            for (text in speechQueue) {
                val gen = generation
                while (holding) delay(40)
                if (gen != generation || text.isBlank()) continue
                speakAndWait(text)
            }
        }
    }

    fun onHoldStart() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            _ui.update {
                it.copy(
                    reply = "Allow the microphone, then hold to talk.",
                    orb = OrbState.IDLE,
                    accent = BrainAccent.PHONE,
                    level = 0f,
                )
            }
            request(Manifest.permission.RECORD_AUDIO)
            return
        }
        generation++
        holding = true
        heard = null
        routed = false
        waitForResult?.cancel()
        routeJob?.cancel()
        speaker.stop()
        drainQueue()
        _ui.update {
            it.copy(
                holding = true,
                orb = OrbState.LISTENING,
                accent = BrainAccent.PHONE,
                level = 0f,
                you = "",
                reply = "",
            )
        }
        recognizer.start(callbacks)
    }

    fun onHoldEnd() {
        if (!holding) return
        holding = false
        _ui.update { it.copy(holding = false) }
        recognizer.stop()
        if (heard != null) {
            routeHeard()
        } else {
            waitForResult = viewModelScope.launch {
                delay(3_500)
                if (!routed && _ui.value.orb == OrbState.LISTENING) {
                    showIdle("I didn't hear anything.")
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val permission = pendingPermission
        pendingPermission = null
        if (permission == Manifest.permission.RECORD_AUDIO && !granted) {
            showProblem("Microphone permission was denied.")
        } else if (granted && permission == Manifest.permission.RECORD_AUDIO) {
            _ui.update { it.copy(reply = "Hold to talk.", orb = OrbState.IDLE, accent = BrainAccent.PHONE) }
        }
    }

    private fun routeHeard() {
        if (routed) return
        val text = heard?.trim().orEmpty()
        routed = true
        waitForResult?.cancel()
        if (text.isEmpty()) {
            showIdle("I didn't hear anything.")
            return
        }
        _ui.update {
            it.copy(
                orb = OrbState.THINKING,
                level = 0f,
                you = text,
                holding = false,
                accent = BrainAccent.PHONE,
            )
        }
        routeJob = viewModelScope.launch {
            val result = try {
                app.router.route(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SkillResult(text = "Something went wrong.", success = false)
            }
            if (!isActive) return@launch
            result.needsPermission?.let { request(it) }
            speechQueue.send(result.text)
        }
    }

    private suspend fun speakAndWait(text: String) {
        _ui.update {
            it.copy(
                orb = OrbState.SPEAKING,
                reply = text,
                level = 0f,
                accent = BrainAccent.PHONE,
            )
        }
        val started = System.nanoTime()
        try {
            kotlinx.coroutines.coroutineScope {
                val levels = launch {
                    while (isActive) {
                        val elapsed = (System.nanoTime() - started) / 1_000_000_000f
                        _ui.update { state ->
                            if (state.orb == OrbState.SPEAKING) {
                                state.copy(level = SpeechEnvelope.levelAt(elapsed))
                            } else {
                                state
                            }
                        }
                        delay(50)
                    }
                }
                suspendCancellableCoroutine { continuation ->
                    speaker.speak(text) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    continuation.invokeOnCancellation { speaker.stop() }
                }
                levels.cancel()
            }
        } finally {
            if (!holding && _ui.value.orb == OrbState.SPEAKING) {
                _ui.update { it.copy(orb = OrbState.IDLE, level = 0f) }
            }
        }
    }

    private fun showProblem(message: String) {
        routed = true
        _ui.update {
            it.copy(
                orb = OrbState.ERROR,
                accent = BrainAccent.OFFLINE,
                level = 0f,
                holding = false,
                reply = message,
            )
        }
        viewModelScope.launch {
            delay(2_200)
            _ui.update { state ->
                if (state.orb == OrbState.ERROR) {
                    state.copy(orb = OrbState.IDLE, accent = BrainAccent.PHONE, level = 0f)
                } else {
                    state
                }
            }
        }
    }

    private fun showIdle(message: String) {
        routed = true
        _ui.update {
            it.copy(
                orb = OrbState.IDLE,
                accent = BrainAccent.PHONE,
                level = 0f,
                holding = false,
                reply = message,
            )
        }
    }

    private fun request(permission: String) {
        pendingPermission = permission
        _events.tryEmit(JarvisEvent.RequestPermission(permission))
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun drainQueue() {
        while (true) {
            speechQueue.tryReceive().getOrNull() ?: break
        }
    }

    override fun onCleared() {
        recognizer.release()
        speaker.shutdown()
        super.onCleared()
    }
}
