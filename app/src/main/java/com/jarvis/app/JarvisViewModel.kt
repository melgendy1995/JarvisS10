package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.audio.JarvisSpeaker
import com.jarvis.audio.PcmMic
import com.jarvis.audio.PcmSpeaker
import com.jarvis.audio.PushToTalkRecognizer
import com.jarvis.audio.SpeechEnvelope
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.LiveEvent
import com.jarvis.core.brain.LiveFollowUp
import com.jarvis.orb.BrainAccent
import com.jarvis.orb.OrbState
import com.jarvis.provider.gemini.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

data class JarvisUiState(
    val orb: OrbState = OrbState.IDLE,
    val level: Float = 0f,
    val accent: BrainAccent = BrainAccent.PHONE,
    val brain: String = "",
    val you: String = "",
    val reply: String = "",
    val holding: Boolean = false,
)

sealed interface JarvisEvent {
    data class RequestPermission(val permission: String) : JarvisEvent
}

private data class Spoken(
    val text: String,
    val accent: BrainAccent,
    val brain: String,
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JarvisApplication
    private val recognizer = PushToTalkRecognizer(application)
    private val speaker = JarvisSpeaker(application)
    private val speechQueue = Channel<Spoken>(capacity = 16)
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
    private var mic: PcmMic? = null
    private var activeFollowUp: LiveFollowUp? = null

    @Volatile private var liveActive = false

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
                speechQueue.send(Spoken(message, BrainAccent.PHONE, "Phone"))
            }
        }
        viewModelScope.launch {
            for (spoken in speechQueue) {
                val gen = generation
                while (holding || liveActive) delay(40)
                if (gen != generation || spoken.text.isBlank()) continue
                speakAndWait(spoken)
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
                    brain = "Phone",
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
        abandonLive()
        speaker.stop()
        drainQueue()
        _ui.update {
            it.copy(
                holding = true,
                orb = OrbState.LISTENING,
                accent = BrainAccent.PHONE,
                brain = "",
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
            _ui.update {
                it.copy(reply = "Hold to talk.", orb = OrbState.IDLE, accent = BrainAccent.PHONE, brain = "Phone")
            }
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
        val gen = generation
        _ui.update {
            it.copy(
                orb = OrbState.THINKING,
                level = 0f,
                you = text,
                holding = false,
                accent = BrainAccent.PHONE,
                brain = "",
            )
        }
        routeJob = viewModelScope.launch {
            val result = try {
                app.chain.reply(BrainRequest(text))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BrainResult.Failure(
                    brainId = "offline",
                    error = com.jarvis.core.brain.BrainError.Unavailable,
                    text = "Something went wrong.",
                )
            }
            if (!isActive || gen != generation) {
                (result as? BrainResult.Answer)?.followUp?.close()
                return@launch
            }
            present(result, gen)
        }
    }

    private suspend fun present(result: BrainResult, gen: Int) {
        when (result) {
            is BrainResult.Answer -> {
                val accent = result.accent.toOrb()
                result.needsPermission?.let { request(it) }
                _ui.update { it.copy(accent = accent, brain = result.accent.label) }
                if (result.audioPcm24k != null || result.followUp != null) {
                    liveActive = true
                    try {
                        val pcm = result.audioPcm24k
                        if (pcm != null && pcm.isNotEmpty()) {
                            _ui.update {
                                it.copy(orb = OrbState.SPEAKING, reply = result.text, level = 0f)
                            }
                            playPcm(pcm)
                        } else if (result.text.isNotBlank()) {
                            speakAndWait(Spoken(result.text, accent, result.accent.label))
                        }
                        val follow = result.followUp
                        if (follow != null && gen == generation && !holding) {
                            runFollowUp(follow)
                        } else {
                            follow?.close()
                        }
                    } finally {
                        liveActive = false
                    }
                } else {
                    speechQueue.send(Spoken(result.text, accent, result.accent.label))
                }
            }
            is BrainResult.Failure -> showProblem(result.text.ifBlank { "Something went wrong." })
        }
    }

    private suspend fun playPcm(pcm: ByteArray) {
        val player = PcmSpeaker(ModelConfig.OUTPUT_SAMPLE_RATE_HZ)
        mic?.muted = true
        try {
            withContext(Dispatchers.IO) {
                if (!player.start()) return@withContext
                val chunk = ModelConfig.OUTPUT_SAMPLE_RATE_HZ / 10 * 2
                var offset = 0
                while (offset < pcm.size && isActive) {
                    val end = minOf(offset + chunk, pcm.size)
                    val level = player.write(pcm.copyOfRange(offset, end))
                    if (level != null) {
                        _ui.update { state ->
                            if (state.orb == OrbState.SPEAKING) state.copy(level = level) else state
                        }
                    }
                    offset = end
                }
                if (isActive) {
                    val durationMs = pcm.size / 2 * 1000L / ModelConfig.OUTPUT_SAMPLE_RATE_HZ + 300L
                    player.drain(durationMs.coerceAtMost(30_000L))
                }
            }
        } finally {
            player.stop()
            mic?.muted = false
        }
    }

    private suspend fun runFollowUp(followUp: LiveFollowUp) {
        activeFollowUp = followUp
        val capture = PcmMic(ModelConfig.INPUT_SAMPLE_RATE_HZ)
        mic = capture
        val frames = Channel<ByteArray>(capacity = 8)
        val incoming = Channel<LiveEvent>(Channel.UNLIMITED)
        val pump = viewModelScope.launch {
            followUp.events.collect { incoming.send(it) }
        }
        val started = capture.start { frame, level ->
            if (capture.muted) return@start
            if (_ui.value.orb == OrbState.LISTENING) {
                _ui.update { it.copy(level = level) }
            }
            frames.trySend(frame)
        }
        if (!started) {
            pump.cancel()
            followUp.close()
            if (mic === capture) mic = null
            activeFollowUp = null
            if (!holding) _ui.update { it.copy(orb = OrbState.IDLE, level = 0f) }
            return
        }
        val send = viewModelScope.launch(Dispatchers.IO) {
            for (frame in frames) {
                if (!capture.muted) followUp.sendMicPcm16k(frame)
            }
        }
        val speaker = PcmSpeaker(ModelConfig.OUTPUT_SAMPLE_RATE_HZ)
        try {
            _ui.update { it.copy(orb = OrbState.LISTENING, level = 0f) }
            speaker.start()
            var listening = true
            var deadline = System.nanoTime() + followUp.followUpWindow.toNanos()
            while (coroutineContext.isActive) {
                val waitNs = if (listening) deadline - System.nanoTime() else 30_000_000_000L
                if (listening && waitNs <= 0L) break
                val event = withTimeoutOrNull(waitNs.coerceAtLeast(1_000_000L) / 1_000_000L) {
                    incoming.receive()
                }
                if (event == null) {
                    if (listening) break else continue
                }
                when (event) {
                    is LiveEvent.Closed -> break
                    is LiveEvent.NeedsPermission -> request(event.permission)
                    is LiveEvent.Transcript -> if (event.text.isNotBlank()) {
                        _ui.update { it.copy(reply = event.text) }
                    }
                    is LiveEvent.Audio -> {
                        listening = false
                        capture.muted = true
                        _ui.update { it.copy(orb = OrbState.SPEAKING) }
                        val level = withContext(Dispatchers.IO) { speaker.write(event.pcm24k) }
                        if (level != null) {
                            _ui.update { state ->
                                if (state.orb == OrbState.SPEAKING) state.copy(level = level) else state
                            }
                        }
                    }
                    LiveEvent.TurnComplete -> {
                        withContext(Dispatchers.IO) { speaker.drain(1_500) }
                        capture.muted = false
                        listening = true
                        deadline = System.nanoTime() + followUp.followUpWindow.toNanos()
                        _ui.update { it.copy(orb = OrbState.LISTENING, level = 0f) }
                    }
                }
            }
        } finally {
            send.cancel()
            pump.cancel()
            speaker.stop()
            capture.stop()
            if (mic === capture) mic = null
            if (activeFollowUp === followUp) activeFollowUp = null
            followUp.close()
            if (!holding && _ui.value.orb != OrbState.ERROR) {
                _ui.update { it.copy(orb = OrbState.IDLE, level = 0f) }
            }
        }
    }

    private suspend fun speakAndWait(spoken: Spoken) {
        _ui.update {
            it.copy(
                orb = OrbState.SPEAKING,
                reply = spoken.text,
                level = 0f,
                accent = spoken.accent,
                brain = spoken.brain,
            )
        }
        mic?.muted = true
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
                    speaker.speak(spoken.text) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    continuation.invokeOnCancellation { speaker.stop() }
                }
                levels.cancel()
            }
        } finally {
            mic?.muted = false
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
                brain = "Offline",
                level = 0f,
                holding = false,
                reply = message,
            )
        }
        viewModelScope.launch {
            delay(2_200)
            _ui.update { state ->
                if (state.orb == OrbState.ERROR) {
                    state.copy(orb = OrbState.IDLE, accent = BrainAccent.PHONE, brain = "", level = 0f)
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
                brain = "Phone",
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

    private fun abandonLive() {
        liveActive = false
        val follow = activeFollowUp
        activeFollowUp = null
        mic?.stop()
        mic = null
        if (follow != null) {
            viewModelScope.launch { follow.close() }
        }
    }

    override fun onCleared() {
        abandonLive()
        recognizer.release()
        speaker.shutdown()
        super.onCleared()
    }
}
