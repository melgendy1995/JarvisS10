package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.system.ListenForegroundService
import com.jarvis.app.system.MediaDuck
import com.jarvis.app.system.WakeLog
import com.jarvis.app.system.WakeRuntime
import com.jarvis.audio.JarvisSpeaker
import com.jarvis.audio.PcmMic
import com.jarvis.audio.PcmSpeaker
import com.jarvis.audio.PushToTalkRecognizer
import com.jarvis.audio.SpeechEnvelope
import com.jarvis.audio.wake.EnergyEndpointer
import com.jarvis.audio.wake.WakeChime
import com.jarvis.audio.wake.WakeConfig
import com.jarvis.audio.wake.WakeHit
import com.jarvis.audio.wake.WakePhrases
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.ConversationTurn
import com.jarvis.core.brain.LiveEvent
import com.jarvis.core.brain.LiveFollowUp
import com.jarvis.core.brain.MemoryFact
import com.jarvis.core.brain.SearchSource
import com.jarvis.core.brain.Speaker
import com.jarvis.data.Channels
import com.jarvis.data.FallbackMemory
import com.jarvis.data.SummaryText
import com.jarvis.data.TurnRole
import com.jarvis.orb.BrainAccent
import com.jarvis.orb.OrbSignals
import com.jarvis.orb.OrbState
import com.jarvis.provider.gemini.ModelConfig
import com.jarvis.provider.groq.BackupSttPolicy
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    val groqSettings: Boolean = false,
    val groqProxyReady: Boolean = false,
    val groqBearerSaved: Boolean = false,
    val groqKeystoreReady: Boolean = true,
    val personaOpen: Boolean = false,
    val personaName: String = "Mahmoud",
    val personaText: String = "",
    val cameraPasswordSaved: Boolean = false,
    val cameraKeystoreReady: Boolean = true,
    val cameraPort: Int = 8088,
    val cameraLan: String = "",
    val cameraTailscale: String = "",
    val weatherLabel: String? = null,
    val sources: List<SearchSource> = emptyList(),
    val searchHtml: String? = null,
)

sealed interface JarvisEvent {
    data class RequestPermission(val permission: String) : JarvisEvent
}

private data class Spoken(
    val text: String,
    val accent: BrainAccent,
    val brain: String,
    val brainId: String,
)

private data class SpeechItem(
    val spoken: Spoken,
    val done: CompletableDeferred<Unit>,
    val handsFree: Boolean = false,
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JarvisApplication
    private val recognizer = PushToTalkRecognizer(application)
    private val speaker = JarvisSpeaker(application)
    private val duck = MediaDuck(application)
    private val chime = WakeChime(application)
    private val speechQueue = Channel<SpeechItem>(capacity = 16)
    private val _ui = MutableStateFlow(JarvisUiState())
    val ui = _ui.asStateFlow()
    private val _events = MutableSharedFlow<JarvisEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private fun pushLevel(value: Float) {
        OrbSignals.level = value.coerceIn(0f, 1f)
    }

    private var holding = false
    private var heard: String? = null
    private var routed = false
    private var generation = 0
    private var routeJob: Job? = null
    private var utteranceJob: Job? = null
    private var waitForResult: Job? = null
    private var pendingPermission: String? = null
    private var mic: PcmMic? = null
    private var activeFollowUp: LiveFollowUp? = null
    private val utteranceMic = PcmMic(getApplication(), ModelConfig.INPUT_SAMPLE_RATE_HZ)
    private val utterancePcm = ByteArrayOutputStream()
    private val pcmLock = Any()
    private var captureOpen = false
    private var pendingPcm = ByteArray(0)
    private var sttFailure = ""
    private var lastBrainId = "phone"
    private var handsFree = false
    private var recognizerBusy = false
    private var bargeSession = 0
    private var bargeStop = false
    private var lastSpoken = ""
    private var listenId = 0
    private var settledListen = -1
    private var askedMic = false

    @Volatile private var liveActive = false

    private val callbacks = PushToTalkRecognizer.Callbacks(
        onLevel = { level ->
            if (holding) pushLevel(level)
        },
        onPartial = { text ->
            if (text.isNotBlank()) _ui.update { it.copy(you = text) }
        },
        onFinal = { text ->
            heard = text
            if (!holding) routeHeard()
        },
        onError = { message, hard ->
            // Soft errors while the finger is down are restarted inside the recognizer
            // and never reach here. Anything that does is a failed on-device transcript.
            holding = false
            sttFailure = message
            freezeUtterancePcm()
            if (!routed) routeHeard(hardFailure = hard)
        },
        onFallbackCapture = {
            // SpeechRecognizer.isRecognitionAvailable was false, or the service never
            // connected. Record the rest of the hold for Whisper. Do not open this
            // capture while the recognizer is listening: a second recorder silences
            // com.google.android.tts on this phone, so the recognizer hears nothing.
            if (holding && !captureOpen) startUtteranceCapture()
        },
    )

    init {
        app.onVoiceUtterance = { text -> onRecognizedUtterance(text) }
        Log.i(VOICE, "voice loop ready")
        viewModelScope.launch {
            app.announcements.collect { message ->
                speechQueue.send(
                    SpeechItem(
                        Spoken(message, BrainAccent.PHONE, "Phone", "phone"),
                        CompletableDeferred(),
                    ),
                )
            }
        }
        viewModelScope.launch {
            for (item in speechQueue) {
                val gen = generation
                while (holding || liveActive || recognizerBusy) delay(40)
                if (gen != generation || item.spoken.text.isBlank()) {
                    if (!item.done.isCompleted) item.done.complete(Unit)
                    if (bargeStop && gen != generation && !holding) {
                        bargeStop = false
                        endHandsFree(quiet = true)
                    } else if (item.handsFree && item.spoken.text.isBlank() && gen == generation && !holding) {
                        endHandsFree(quiet = true)
                    }
                    continue
                }
                val follow = item.handsFree && handsFree
                try {
                    speakAndWait(item.spoken, resumeMic = !follow)
                } finally {
                    if (!item.done.isCompleted) item.done.complete(Unit)
                }
                if (gen != generation || holding) {
                    if (bargeStop && !holding) {
                        bargeStop = false
                        endHandsFree(quiet = true)
                    }
                    continue
                }
                if (follow && handsFree) startFollowUp(gen)
            }
        }
        viewModelScope.launch {
            WakeRuntime.triggers.collect { hit -> onWakeHit(hit) }
        }
    }

    fun onHoldStart() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            _ui.update {
                it.copy(
                    reply = "Allow the microphone, then tap the orb.",
                    orb = OrbState.IDLE,
                    accent = BrainAccent.PHONE,
                    brain = "Phone",
                    level = 0f,
                )
            }
            request(Manifest.permission.RECORD_AUDIO)
            return
        }
        val gen = ++generation
        holding = true
        handsFree = false
        recognizerBusy = false
        bargeStop = false
        bargeSession++
        listenId++
        pushLevel(0f)
        heard = null
        routed = false
        sttFailure = ""
        pendingPcm = ByteArray(0)
        waitForResult?.cancel()
        routeJob?.cancel()
        utteranceJob?.cancel()
        abandonLive()
        speaker.stop()
        recognizer.discard()
        drainQueue()
        duck.release()
        _ui.update {
            it.copy(
                holding = true,
                orb = OrbState.LISTENING,
                accent = BrainAccent.PHONE,
                brain = "",
                level = 0f,
                you = "",
                reply = "",
                sources = emptyList(),
                searchHtml = null,
            )
        }
        WakeLog.state("HOLDING")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WakeRuntime.pauseBlocking() }
            if (!holding || gen != generation) {
                if (!holding && gen == generation && !recognizerBusy) WakeRuntime.resume(speaking = false)
                return@launch
            }
            recognizer.start(callbacks)
        }
    }

    fun onHoldEnd() {
        if (!holding) return
        val gen = generation
        holding = false
        pushLevel(0f)
        _ui.update { it.copy(holding = false) }
        freezeUtterancePcm()
        recognizer.stop()
        if (heard != null) {
            routeHeard()
        } else {
            waitForResult = viewModelScope.launch {
                delay(3_500)
                if (gen != generation || holding || routed) return@launch
                if (_ui.value.orb == OrbState.LISTENING) {
                    showIdle("I didn't hear anything.")
                }
            }
        }
    }

    /** Android 12 starts the microphone foreground service only while this screen is visible. */
    fun onScreenVisible() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            if (!askedMic) {
                askedMic = true
                request(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        viewModelScope.launch {
            ListenForegroundService.startWhileVisible(getApplication())
        }
    }

    fun openGroqSettings() {
        val ends = app.cameraEndpoints()
        _ui.update {
            it.copy(
                groqSettings = true,
                personaOpen = false,
                groqProxyReady = app.groqProxyConfigured(),
                groqBearerSaved = app.groqBearerSaved(),
                groqKeystoreReady = app.groqKeystoreOpen(),
                cameraPasswordSaved = app.cameraPasswordSaved(),
                cameraKeystoreReady = app.cameraKeystoreOpen(),
                cameraPort = app.cameraPort(),
                cameraLan = ends.lanText(),
                cameraTailscale = ends.tailscaleText(),
            )
        }
    }

    fun openPersona() {
        viewModelScope.launch {
            val profile = app.memory.profile()
            _ui.update {
                it.copy(
                    personaOpen = true,
                    groqSettings = false,
                    personaName = profile.name,
                    personaText = profile.persona,
                )
            }
        }
    }

    fun closePersona() {
        _ui.update { it.copy(personaOpen = false) }
    }

    fun savePersona(text: String) {
        viewModelScope.launch {
            app.memory.savePersona(text)
            _ui.update { it.copy(personaOpen = false, personaText = text) }
        }
    }

    fun closeGroqSettings() {
        _ui.update { it.copy(groqSettings = false) }
    }

    fun saveGroqBearer(token: String) {
        val saved = app.saveGroqBearer(token)
        _ui.update {
            it.copy(
                groqSettings = !saved,
                groqBearerSaved = app.groqBearerSaved(),
                groqKeystoreReady = app.groqKeystoreOpen(),
            )
        }
    }

    /** A blank password field does not erase a password that is already stored. */
    fun saveCameraAccess(password: String, portText: String) {
        val port = portText.trim().toIntOrNull()
        if (port != null) app.saveCameraPort(port)
        if (password.isNotEmpty()) app.saveCameraPassword(password)
        val ends = app.cameraEndpoints()
        _ui.update {
            it.copy(
                cameraPasswordSaved = app.cameraPasswordSaved(),
                cameraKeystoreReady = app.cameraKeystoreOpen(),
                cameraPort = app.cameraPort(),
                cameraLan = ends.lanText(),
                cameraTailscale = ends.tailscaleText(),
            )
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val permission = pendingPermission
        pendingPermission = null
        if (permission == Manifest.permission.RECORD_AUDIO && !granted) {
            showProblem("Microphone permission was denied.")
        } else if (granted && permission == Manifest.permission.RECORD_AUDIO) {
            _ui.update {
                it.copy(reply = "Tap or hold the orb. Say Hey Jarvis with the screen open once.", orb = OrbState.IDLE, accent = BrainAccent.PHONE, brain = "Phone")
            }
            viewModelScope.launch {
                ListenForegroundService.startWhileVisible(getApplication())
            }
        }
    }

    private fun routeHeard(hardFailure: Boolean = false) {
        if (routed) return
        val text = heard?.trim().orEmpty()
        if (text.isEmpty()) {
            val audio = pendingPcm
            pendingPcm = ByteArray(0)
            // Whisper is only the backup recognizer. The router still runs on its text.
            if (BackupSttPolicy.shouldTranscribe(
                    recognizerText = text,
                    recognizerFailed = true,
                    pcmBytes = audio.size,
                    cloudNext = app.cloudBrainReachable() && app.whisper.ready(),
                )
            ) {
                routed = true
                waitForResult?.cancel()
                transcribe(audio)
                return
            }
            routed = true
            waitForResult?.cancel()
            if (hardFailure && sttFailure.isNotBlank()) {
                showProblem(sttFailure)
            } else {
                showIdle(sttFailure.ifBlank { "I didn't hear anything." })
            }
            return
        }
        routed = true
        pendingPcm = ByteArray(0)
        waitForResult?.cancel()
        deliver(text)
    }

    private fun transcribe(audio: ByteArray) {
        val gen = generation
        _ui.update {
            it.copy(
                orb = OrbState.THINKING,
                level = 0f,
                you = "",
                holding = false,
                accent = BrainAccent.PHONE,
                brain = "",
            )
        }
        routeJob = viewModelScope.launch {
            val text = try {
                app.whisper.transcribe(audio)?.trim().orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
            if (!isActive || gen != generation) return@launch
            if (text.isEmpty()) {
                showIdle(sttFailure.ifBlank { "I didn't hear anything." })
                return@launch
            }
            deliver(text)
        }
    }

    private fun deliver(text: String, handsFreeTurn: Boolean = false) {
        utteranceJob?.cancel()
        val gen = generation
        utteranceJob = app.voiceScope.launch {
            onRecognizedUtterance(text, gen, handsFreeTurn)
        }
    }

    /**
     * Recognizer result and DEBUG_ASK both enter here. The chain runs to the end
     * on the application voice scope, then this function speaks the answer.
     */
    private suspend fun onRecognizedUtterance(
        text: String,
        gen: Int = generation,
        handsFreeTurn: Boolean = false,
    ) {
        Log.i(VOICE, "utterance begin chars=${text.length}")
        pushLevel(0f)
        _ui.update {
            it.copy(
                orb = OrbState.THINKING,
                level = 0f,
                you = text,
                holding = false,
                accent = BrainAccent.PHONE,
                brain = "",
                sources = emptyList(),
                searchHtml = null,
            )
        }
        val result = try {
            withContext(Dispatchers.IO) {
                app.chain.reply(brainRequest(text))
            }
        } catch (cancelled: CancellationException) {
            Log.w(VOICE, "utterance cancelled")
            throw cancelled
        } catch (_: Exception) {
            BrainResult.Failure(
                brainId = "offline",
                error = com.jarvis.core.brain.BrainError.Unavailable,
                text = "Something went wrong.",
            )
        }
        if (!coroutineContext.isActive || gen != generation) {
            Log.i(VOICE, "utterance dropped")
            (result as? BrainResult.Answer)?.followUp?.close()
            return
        }
        val brainId = when (result) {
            is BrainResult.Answer -> result.brainId
            is BrainResult.Failure -> result.brainId
        }
        lastBrainId = brainId
        Log.i(VOICE, "utterance result brain=$brainId")
        if (result is BrainResult.Answer) {
            record(TurnRole.USER, text, brainId)
            record(TurnRole.ASSISTANT, result.text, brainId)
        }
        present(result, gen, handsFreeTurn)
    }

    private suspend fun present(result: BrainResult, gen: Int, handsFreeTurn: Boolean) {
        when (result) {
            is BrainResult.Answer -> {
                val accent = result.accent.toOrb()
                result.needsPermission?.let { request(it) }
                _ui.update {
                    it.copy(
                        accent = accent,
                        brain = result.accent.label,
                        weatherLabel = result.shelf ?: it.weatherLabel,
                        sources = result.search?.sources.orEmpty(),
                        searchHtml = result.search?.suggestionsHtml,
                    )
                }
                if (result.audioPcm24k != null || result.followUp != null) {
                    liveActive = true
                    var didLiveFollow = false
                    try {
                        withContext(Dispatchers.IO) { WakeRuntime.pauseBlocking() }
                        val pcm = result.audioPcm24k
                        if (pcm != null && pcm.isNotEmpty() && gen == generation && !holding) {
                            pushLevel(0f)
                            _ui.update {
                                it.copy(orb = OrbState.SPEAKING, reply = result.text, level = 0f)
                            }
                            WakeRuntime.resume(speaking = true)
                            try {
                                playPcm(pcm)
                            } finally {
                                withContext(Dispatchers.IO) { WakeRuntime.pauseBlocking() }
                            }
                        } else if (result.text.isNotBlank() && gen == generation) {
                            speakAndWait(
                                Spoken(result.text, accent, result.accent.label, result.brainId),
                                resumeMic = false,
                            )
                        }
                        val follow = result.followUp
                        if (follow != null && gen == generation && !holding) {
                            didLiveFollow = true
                            runFollowUp(follow)
                        } else {
                            follow?.close()
                        }
                    } finally {
                        liveActive = false
                    }
                    if (gen == generation && !holding) {
                        if (handsFreeTurn && handsFree && !didLiveFollow) {
                            startFollowUp(gen)
                        } else {
                            endHandsFree(quiet = true)
                        }
                    } else if (bargeStop && !holding) {
                        bargeStop = false
                        endHandsFree(quiet = true)
                    }
                } else {
                    speakQueued(
                        Spoken(result.text, accent, result.accent.label, result.brainId),
                        handsFreeTurn && handsFree,
                    )
                }
            }
            is BrainResult.Failure -> {
                val message = result.text.ifBlank { "Something went wrong." }
                speakQueued(
                    Spoken(
                        message,
                        com.jarvis.core.brain.BrainAccent.OFFLINE.toOrb(),
                        "Offline",
                        result.brainId,
                    ),
                    handsFreeTurn && handsFree,
                )
            }
        }
    }

    private suspend fun speakQueued(spoken: Spoken, handsFreeTurn: Boolean) {
        val done = CompletableDeferred<Unit>()
        speechQueue.send(SpeechItem(spoken, done, handsFreeTurn))
        done.await()
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
                    val sample = player.write(pcm.copyOfRange(offset, end))
                    if (sample != null && _ui.value.orb == OrbState.SPEAKING) pushLevel(sample)
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
        val capture = PcmMic(getApplication(), ModelConfig.INPUT_SAMPLE_RATE_HZ)
        mic = capture
        val frames = Channel<ByteArray>(capacity = 8)
        val incoming = Channel<LiveEvent>(Channel.UNLIMITED)
        val pump = viewModelScope.launch {
            followUp.events.collect { incoming.send(it) }
        }
        val started = capture.start { frame, level ->
            if (capture.muted) return@start
            if (_ui.value.orb == OrbState.LISTENING) pushLevel(level)
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
            pushLevel(0f)
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
                        pushLevel(0f)
                        val sample = withContext(Dispatchers.IO) { speaker.write(event.pcm24k) }
                        if (sample != null && _ui.value.orb == OrbState.SPEAKING) pushLevel(sample)
                    }
                    LiveEvent.TurnComplete -> {
                        record(TurnRole.ASSISTANT, _ui.value.reply, lastBrainId)
                        withContext(Dispatchers.IO) { speaker.drain(1_500) }
                        capture.muted = false
                        listening = true
                        deadline = System.nanoTime() + followUp.followUpWindow.toNanos()
                        pushLevel(0f)
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

    private suspend fun speakAndWait(spoken: Spoken, resumeMic: Boolean) {
        Log.i(
            VOICE,
            "tts speak brain=${spoken.brainId} chars=${spoken.text.length} preview=${spokenPreview(spoken.text)}",
        )
        lastSpoken = spoken.text
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
        pushLevel(0f)
        WakeLog.state("SPEAKING")
        ListenForegroundService.note(getApplication(), "Speaking")
        val token = ++bargeSession
        withContext(Dispatchers.IO) { WakeRuntime.pauseBlocking() }
        if (token == bargeSession && !holding) startBargeListen(token)
        val started = System.nanoTime()
        try {
            kotlinx.coroutines.coroutineScope {
                val levels = launch {
                    while (isActive) {
                        val elapsed = (System.nanoTime() - started) / 1_000_000_000f
                        if (_ui.value.orb == OrbState.SPEAKING) {
                            pushLevel(SpeechEnvelope.levelAt(elapsed))
                        }
                        delay(50)
                    }
                }
                suspendCancellableCoroutine { continuation ->
                    speaker.speak(spoken.text, spoken.brainId) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    continuation.invokeOnCancellation { speaker.stop() }
                }
                levels.cancel()
            }
        } finally {
            mic?.muted = false
            if (token == bargeSession) {
                bargeSession++
                recognizer.discard()
                // discard() posts to the main looper. Leave this continuation so that
                // post runs before another recorder opens.
                withContext(Dispatchers.IO) { }
                if (!holding && resumeMic && !bargeStop) {
                    if (_ui.value.orb == OrbState.SPEAKING) {
                        _ui.update { it.copy(orb = OrbState.IDLE, level = 0f) }
                    }
                    WakeRuntime.resume(speaking = false)
                    if (!handsFree) {
                        WakeLog.state("PASSIVE")
                        ListenForegroundService.note(getApplication(), "Listening for Hey Jarvis")
                    }
                }
            }
        }
    }

    private fun onWakeHit(hit: WakeHit) {
        if (holding) {
            WakeLog.i("trigger ignored while holding")
            return
        }
        val score = String.format(Locale.US, "%.3f", hit.score)
        WakeLog.trigger("score=$score duringSpeech=${hit.duringSpeech}")
        val gen = ++generation
        handsFree = true
        bargeStop = false
        bargeSession++
        listenId++
        recognizer.discard()
        speaker.stop()
        abandonLive()
        utteranceJob?.cancel()
        routeJob?.cancel()
        waitForResult?.cancel()
        armCommandListen(gen, playChime = true)
    }

    private fun armCommandListen(gen: Int, playChime: Boolean) {
        recognizerBusy = true
        heard = null
        routed = false
        sttFailure = ""
        pendingPcm = ByteArray(0)
        WakeLog.state("RECOGNIZING")
        ListenForegroundService.note(getApplication(), "Listening")
        pushLevel(0f)
        _ui.update {
            it.copy(
                orb = OrbState.LISTENING,
                level = 0f,
                you = "",
                holding = false,
                accent = BrainAccent.PHONE,
                brain = "",
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { WakeRuntime.pauseBlocking() }
            if (gen != generation || holding) {
                if (gen == generation) recognizerBusy = false
                return@launch
            }
            duck.acquireIfPlaying()
            if (playChime) withContext(Dispatchers.IO) { chime.playAndWait() }
            if (gen != generation || holding) return@launch
            listenHandsFree(gen, followUp = false)
        }
    }

    private fun startFollowUp(gen: Int) {
        if (gen != generation || holding || !handsFree) return
        WakeLog.state("FOLLOW_UP")
        ListenForegroundService.note(getApplication(), "Listening")
        pushLevel(0f)
        _ui.update { it.copy(orb = OrbState.LISTENING, level = 0f, you = "") }
        listenHandsFree(gen, followUp = true)
    }

    private fun listenHandsFree(gen: Int, followUp: Boolean) {
        val id = ++listenId
        val openedAt = SystemClock.elapsedRealtime()
        val ended = AtomicBoolean(false)
        val endpointer = EnergyEndpointer(
            noSpeechTimeoutMs = if (followUp) WakeConfig.FOLLOW_UP_MS else 6_000L,
        )
        var restarts = 0
        lateinit var callbacks: PushToTalkRecognizer.Callbacks
        callbacks = PushToTalkRecognizer.Callbacks(
            onLevel = { level ->
                if (id == listenId && _ui.value.orb == OrbState.LISTENING) pushLevel(level)
            },
            onPartial = { text ->
                if (id != listenId || gen != generation || holding || text.isBlank()) return@Callbacks
                _ui.update { it.copy(you = text) }
            },
            onFinal = { text ->
                if (id != listenId || gen != generation || holding) return@Callbacks
                val elapsed = SystemClock.elapsedRealtime() - openedAt
                val stillOpen = followUp && text.isBlank() && !endpointer.heardSpeech &&
                    restarts < 4 && elapsed < WakeConfig.FOLLOW_UP_MS - 400
                if (stillOpen) {
                    restarts++
                    ended.set(false)
                    WakeLog.i("follow-up recognizer restarted $restarts elapsed=${elapsed}ms")
                    recognizer.listenOnce(callbacks)
                    return@Callbacks
                }
                finishHandsFree(id, text, gen, followUp, hard = false)
            },
            onError = { message, hard ->
                if (id != listenId || gen != generation || holding) return@Callbacks
                sttFailure = message
                finishHandsFree(id, "", gen, followUp, hard)
            },
            onFallbackCapture = {
                if (id != listenId || gen != generation || holding) return@Callbacks
                finishHandsFree(id, "", gen, followUp, hard = false)
            },
            onRmsDb = { db ->
                if (id != listenId || gen != generation || holding || ended.get()) return@Callbacks
                if (endpointer.onRms(SystemClock.elapsedRealtime(), db) == EnergyEndpointer.Decision.END) {
                    if (ended.compareAndSet(false, true)) recognizer.stopOnce()
                }
            },
        )
        recognizerBusy = true
        recognizer.listenOnce(callbacks)
    }

    private fun finishHandsFree(id: Int, text: String, gen: Int, followUp: Boolean, hard: Boolean) {
        if (id == settledListen || id != listenId || gen != generation || holding) return
        settledListen = id
        recognizerBusy = false
        val line = text.trim()
        if (line.isEmpty() || WakePhrases.isStop(line)) {
            if (!followUp && line.isEmpty()) WakeLog.falseTrigger()
            val quiet = followUp || WakePhrases.isStop(line)
            val message = when {
                quiet -> ""
                hard && sttFailure.isNotBlank() -> sttFailure
                else -> sttFailure.ifBlank { "I didn't hear anything." }
            }
            endHandsFree(quiet = quiet, message = message)
            return
        }
        WakeLog.state("THINKING")
        pushLevel(0f)
        _ui.update {
            it.copy(
                orb = OrbState.THINKING,
                level = 0f,
                you = line,
                holding = false,
                brain = "",
                accent = BrainAccent.PHONE,
            )
        }
        deliver(line, handsFreeTurn = true)
    }

    private fun endHandsFree(quiet: Boolean, message: String = "") {
        if (holding) return
        handsFree = false
        recognizerBusy = false
        bargeStop = false
        duck.release()
        WakeRuntime.resume(speaking = false)
        WakeLog.state("PASSIVE")
        ListenForegroundService.note(getApplication(), "Listening for Hey Jarvis")
        if (!quiet && message.isNotBlank()) {
            showIdle(message)
            return
        }
        if (_ui.value.orb != OrbState.ERROR) {
            pushLevel(0f)
            _ui.update { it.copy(orb = OrbState.IDLE, level = 0f, holding = false) }
        }
    }

    private fun startBargeListen(token: Int) {
        recognizer.listenOnce(
            keepAlive = true,
            callbacks = PushToTalkRecognizer.Callbacks(
                onLevel = {},
                onPartial = { text -> onBargeText(token, text, fromFinal = false) },
                onFinal = { text -> onBargeText(token, text, fromFinal = true) },
                onError = { _, _ -> restartBarge(token) },
                onFallbackCapture = {
                    if (token != bargeSession || holding) return@Callbacks
                    WakeLog.i("barge recognizer unavailable, model gate ${WakeConfig.TTS_THRESHOLD}")
                    WakeRuntime.resume(speaking = true)
                },
            ),
        )
    }

    private fun restartBarge(token: Int) {
        if (token != bargeSession || holding) return
        viewModelScope.launch {
            delay(200)
            if (token != bargeSession || holding) return@launch
            if (_ui.value.orb != OrbState.SPEAKING) return@launch
            startBargeListen(token)
        }
    }

    private fun onBargeText(token: Int, text: String, fromFinal: Boolean) {
        if (token != bargeSession || holding || text.isBlank()) return
        when (WakePhrases.barge(text, lastSpoken)) {
            WakePhrases.Barge.NONE -> if (fromFinal) restartBarge(token)
            WakePhrases.Barge.STOP -> {
                WakeLog.i("barge stop")
                generation++
                bargeStop = true
                bargeSession++
                listenId++
                speaker.stop()
                recognizer.discard()
            }
            WakePhrases.Barge.WAKE -> {
                val rest = WakePhrases.remainder(text)
                WakeLog.i("barge wake rest=${!rest.isNullOrBlank()}")
                val gen = ++generation
                bargeStop = false
                bargeSession++
                handsFree = true
                listenId++
                speaker.stop()
                recognizer.discard()
                utteranceJob?.cancel()
                if (!rest.isNullOrBlank()) {
                    recognizerBusy = false
                    WakeLog.state("THINKING")
                    pushLevel(0f)
                    _ui.update {
                        it.copy(
                            orb = OrbState.THINKING,
                            level = 0f,
                            you = rest,
                            holding = false,
                            brain = "",
                            accent = BrainAccent.PHONE,
                        )
                    }
                    deliver(rest, handsFreeTurn = true)
                } else {
                    armCommandListen(gen, playChime = true)
                }
            }
        }
    }

    private fun showProblem(message: String) {
        routed = true
        if (!holding && !recognizerBusy && !handsFree) WakeRuntime.resume(speaking = false)
        pushLevel(0f)
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
        if (!holding && !recognizerBusy && !handsFree) WakeRuntime.resume(speaking = false)
        pushLevel(0f)
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
            val item = speechQueue.tryReceive().getOrNull() ?: break
            if (!item.done.isCompleted) item.done.complete(Unit)
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

    private fun startUtteranceCapture() {
        synchronized(pcmLock) { utterancePcm.reset() }
        captureOpen = utteranceMic.start { frame, level ->
            if (holding) pushLevel(level)
            synchronized(pcmLock) {
                if (utterancePcm.size() >= MAX_UTTERANCE_BYTES) return@synchronized
                val room = MAX_UTTERANCE_BYTES - utterancePcm.size()
                if (frame.size <= room) {
                    utterancePcm.write(frame)
                } else {
                    utterancePcm.write(frame, 0, room)
                }
            }
        }
    }

    private fun freezeUtterancePcm() {
        if (!captureOpen) return
        utteranceMic.stop()
        captureOpen = false
        pendingPcm = synchronized(pcmLock) {
            val bytes = utterancePcm.toByteArray()
            utterancePcm.reset()
            bytes
        }
    }

    private suspend fun brainRequest(text: String): BrainRequest {
        val snap = app.memory.snapshot(Channels.VOICE)
        return BrainRequest(
            utterance = text,
            turns = snap.turns.map { turn ->
                ConversationTurn(
                    speaker = if (turn.role == TurnRole.USER) Speaker.USER else Speaker.ASSISTANT,
                    text = turn.text,
                )
            },
            persona = snap.persona,
            facts = snap.facts.map { MemoryFact(it.key, it.value, it.pinned) },
            summary = snap.summary,
        )
    }

    private suspend fun record(role: String, text: String, brainId: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        val outcome = app.memory.appendTurn(role, line, brainId, Channels.VOICE)
        if (!outcome.askCloud || outcome.localSummary.isBlank()) return
        if (FallbackMemory.isFallbackText(outcome.localSummary)) return
        val stamp = outcome.summaryUpdatedAt
        val prompt = SummaryText.cloudPrompt(outcome.localSummary)
        viewModelScope.launch {
            val rewritten = app.rewriteSummary(prompt)
            if (!rewritten.isNullOrBlank()) app.memory.replaceSummary(stamp, rewritten)
        }
    }

    override fun onCleared() {
        app.onVoiceUtterance = null
        utteranceJob?.cancel()
        if (captureOpen) utteranceMic.stop()
        captureOpen = false
        abandonLive()
        duck.release()
        recognizer.release()
        speaker.shutdown()
        super.onCleared()
    }

    private companion object {
        const val VOICE = "JarvisVoice"
        const val MAX_UTTERANCE_BYTES = 60 * 16_000 * 2

        fun spokenPreview(text: String): String {
            return text.replace('\n', ' ')
                .replace(Regex("""gsk_[A-Za-z0-9_\-]+"""), "gsk_[redacted]")
                .replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
                .replace(Regex("""ya29\.[A-Za-z0-9_\-.]+"""), "ya29.[redacted]")
                .take(80)
        }
    }
}
