package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.system.AndroidCoarseLocation
import com.jarvis.app.system.LanAddress
import com.jarvis.app.system.ListenForegroundService
import com.jarvis.app.system.MediaDuck
import com.jarvis.app.system.OpenMeteoClient
import com.jarvis.app.system.ShelfWeather
import com.jarvis.app.system.VoiceLanguageStore
import com.jarvis.app.system.WakeLog
import com.jarvis.app.system.WakeRuntime
import com.jarvis.audio.JarvisSpeaker
import com.jarvis.audio.PcmLevel
import com.jarvis.audio.PcmMic
import com.jarvis.audio.PcmSpeaker
import com.jarvis.audio.PushToTalkRecognizer
import com.jarvis.audio.SpeechEnvelope
import com.jarvis.audio.UtteranceMic
import com.jarvis.audio.wake.EnergyEndpointer
import com.jarvis.audio.wake.WakeChime
import com.jarvis.audio.wake.WakeConfig
import com.jarvis.audio.wake.WakeHit
import com.jarvis.audio.wake.WakePhrases
import com.jarvis.audio.wake.WakeSession
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.ConversationTurn
import com.jarvis.core.brain.LiveEvent
import com.jarvis.core.brain.LiveFollowUp
import com.jarvis.core.brain.MemoryFact
import com.jarvis.core.brain.SearchSource
import com.jarvis.core.brain.Speaker
import com.jarvis.core.skills.CoarseFix
import com.jarvis.core.skills.WeatherClient
import com.jarvis.data.Channels
import com.jarvis.data.FallbackMemory
import com.jarvis.data.SummaryText
import com.jarvis.data.TurnRole
import com.jarvis.orb.BrainAccent
import com.jarvis.orb.OrbSignals
import com.jarvis.orb.OrbState
import com.jarvis.provider.gemini.ModelConfig
import com.jarvis.provider.groq.ArabicRecheckPolicy
import com.jarvis.provider.groq.BackupSttPolicy
import com.jarvis.provider.groq.GroqTranscript
import com.jarvis.provider.groq.WhisperAcceptPolicy
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
    val voiceLanguage: String = VoiceLanguageStore.AUTO,
    val shelfLan: String = "No Wi-Fi",
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
    private val recognizer = PushToTalkRecognizer(
        context = application,
        voiceLanguage = { VoiceLanguageStore.get(application) },
    )
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

    /** Wi-Fi LAN address plus the camera port for the shelf, e.g. 192.168.8.211:8088, with the Tailscale address appended when one is up. */
    private fun refreshLan() {
        viewModelScope.launch {
            val (ip, ts) =
                withContext(Dispatchers.IO) {
                    LanAddress.currentCandidates()
                        .let { LanAddress.pick(it) to LanAddress.pickTailscale(it) }
                }
            val text = LanAddress.display(ip, app.cameraPort(), ts)
            if (_ui.value.shelfLan != text) _ui.update { it.copy(shelfLan = text) }
        }
    }

    /**
     * Current weather for the WeatherSkill default source: the phone's coarse fix
     * when it is already granted (never prompted from here), else Riyadh. The label
     * mirrors WeatherSkill's shelf so a spoken answer still overwrites it later.
     */
    private suspend fun weatherLoop() {
        val client = OpenMeteoClient()
        val fixes = AndroidCoarseLocation(app)
        while (coroutineContext.isActive) {
            val ok = runCatching { refreshWeatherLabel(client, fixes) }.getOrDefault(false)
            delay(ShelfWeather.nextDelay(ok))
        }
    }

    private suspend fun refreshWeatherLabel(client: WeatherClient, fixes: CoarseFix): Boolean {
        val point = if (fixes.permitted) {
            try {
                fixes.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val device = point != null
        val reading = withContext(Dispatchers.IO) {
            client.fetch(
                point?.latitude ?: ShelfWeather.RIYADH_LAT,
                point?.longitude ?: ShelfWeather.RIYADH_LON,
            )
        }
        return if (reading == null) {
            Log.i(ShelfWeather.TAG, "shelf refresh failed reason=empty")
            false
        } else {
            val label = ShelfWeather.label(device, reading)
            if (label != null && label != _ui.value.weatherLabel) {
                _ui.update { it.copy(weatherLabel = label) }
            }
            Log.i(ShelfWeather.TAG, "shelf refresh ok temp=${reading.celsius}")
            true
        }
    }

    private var holding = false
    private var heard: String? = null
    private var heardConfidence = -1f
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
    /** Jarvis's own one-turn recorder while the Whisper-primary path owns the mic (ar/auto). */
    private var ownMic: UtteranceMic? = null

    @Volatile private var ownCaptureActive = false
    private val wake = WakeRuntime.session

    @Volatile private var liveActive = false

    private fun resumeWakePassive(reason: String) {
        if (holding) return
        val was = wake.phase
        wake.enter(WakeSession.Phase.PASSIVE)
        handsFree = false
        recognizerBusy = false
        WakeRuntime.resume(speaking = false)
        WakeLog.state("PASSIVE")
        if (reason != "visible" || was != WakeSession.Phase.PASSIVE) {
            WakeLog.i("resume $reason")
        }
        ListenForegroundService.note(getApplication(), "Listening for Hey Jarvis")
    }

    private val callbacks = PushToTalkRecognizer.Callbacks(
        onLevel = { level ->
            if (holding) pushLevel(level)
        },
        onPartial = { text ->
            if (text.isNotBlank()) _ui.update { it.copy(you = text) }
        },
        onFinal = { text, confidence ->
            heard = text
            heardConfidence = confidence
            logHeard(text, confidence)
            if (!holding) routeHeard()
        },
        onError = { message, hard ->
            // Soft errors while the finger is down are restarted inside the recognizer
            // and never reach here. Anything that does is a failed on-device transcript.
            holding = false
            if (message.contains("busy", ignoreCase = true)) wake.recognizerBusy() else wake.error()
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
        app.onWhisperPrimaryUtterance = { pcm -> debugWhisperPrimary(pcm) }
        app.onDebugListen = { debugListen() }
        app.onDebugTtsVoices = { speaker.logVoices() }
        Log.i(VOICE, "voice loop ready")
        // The shelf address text follows Wi-Fi changes a little slowly on purpose: ~30 s.
        viewModelScope.launch {
            while (isActive) {
                refreshLan()
                delay(LAN_REFRESH_MILLIS)
            }
        }
        // The shelf weather starts with a fetch so it never shows "--" after launch,
        // then keeps to the 30-minute refresh with a 2-minute retry on failure.
        viewModelScope.launch { weatherLoop() }
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
        wake.holdStart()
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
        stopOwnCapture()
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
                if (!holding && gen == generation) resumeWakePassive("hold cancelled")
                return@launch
            }
            if (ownCaptureMode()) ownCaptureHold(gen) else recognizer.start(callbacks)
        }
    }

    fun onHoldEnd() {
        if (!holding) return
        val gen = generation
        holding = false
        wake.holdRelease()
        pushLevel(0f)
        _ui.update { it.copy(holding = false) }
        freezeUtterancePcm()
        recognizer.stop()
        if (ownCaptureActive) {
            // The own-capture turn owns the routing from here: stopping the recorder
            // finishes the capture, and its coroutine transcribes and routes.
            ownMic?.stop()
            return
        }
        if (heard != null) {
            routeHeard()
        } else {
            waitForResult = viewModelScope.launch {
                delay(3_500)
                if (gen != generation || holding || routed) return@launch
                wake.emptyTranscript()
                resumeWakePassive("hold timeout")
                if (_ui.value.orb == OrbState.LISTENING || _ui.value.orb == OrbState.IDLE) {
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
            wake.appVisible()
            if (!holding && wake.shouldCapture) resumeWakePassive("visible")
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
                voiceLanguage = VoiceLanguageStore.get(getApplication()),
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

    fun setVoiceLanguage(value: String) {
        VoiceLanguageStore.set(getApplication(), value)
        Log.i(VOICE, "voice language set=$value")
        _ui.update { it.copy(voiceLanguage = VoiceLanguageStore.get(getApplication())) }
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
    fun saveCameraAccess(password: String, portText: String): Boolean {
        val port = portText.trim().toIntOrNull()
        val portSaved = port != null && app.saveCameraPort(port)
        if (password.isNotEmpty()) app.saveCameraPassword(password)
        if (portSaved) refreshLan()
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
        return portSaved
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
                wake.appVisible()
                if (!holding && wake.shouldCapture) resumeWakePassive("permission")
            }
        }
    }

    private fun routeHeard(hardFailure: Boolean = false) {
        if (routed) return
        val text = heard?.trim().orEmpty()
        if (text.isEmpty()) {
            val audio = pendingPcm
            pendingPcm = ByteArray(0)
            val setting = VoiceLanguageStore.get(getApplication())
            val auto = setting == VoiceLanguageStore.AUTO
            // AUTO is a Whisper safety net: no cloud brain needed beyond whisper itself,
            // but the capture must hold real speech-level audio, not room noise.
            val cloudNext = if (auto) {
                app.whisper.ready()
            } else {
                app.cloudBrainReachable() && app.whisper.ready()
            }
            val run = BackupSttPolicy.shouldTranscribe(
                recognizerText = text,
                recognizerFailed = true,
                pcmBytes = audio.size,
                cloudNext = cloudNext,
                arabicAuto = auto,
                pcmRms = rmsInt(audio),
            )
            Log.i(VOICE, "stt recheck decision=${if (run) "run" else "skip"} reason=${backupReason(run, setting, audio)}")
            if (run) {
                routed = true
                waitForResult?.cancel()
                transcribe(audio)
                return
            }
            routed = true
            waitForResult?.cancel()
            wake.emptyTranscript()
            if (hardFailure && sttFailure.isNotBlank()) {
                showProblem(sttFailure)
            } else {
                showIdle(sttFailure.ifBlank { "I didn't hear anything." })
            }
            return
        }
        routed = true
        wake.commandHeard()
        val audio = pendingPcm
        pendingPcm = ByteArray(0)
        waitForResult?.cancel()
        // Auto mode only: pinned English or Arabic needs no Whisper re-check.
        val setting = VoiceLanguageStore.get(getApplication())
        val ready = app.whisper.ready()
        val recheck = setting == VoiceLanguageStore.AUTO &&
            ArabicRecheckPolicy.shouldRecheck(
                whisperReady = ready,
                pcmBytes = audio.size,
                recognizerText = text,
                matchesLocalSkill = app.matchesLocalSkill(text),
                confidence = heardConfidence,
            )
        Log.i(VOICE, "stt recheck decision=${if (recheck) "run" else "skip"} reason=${recheckReason(setting, ready, audio.size, text, app.matchesLocalSkill(text))}")
        if (recheck) {
            recheckTranscript(text, heardConfidence, audio)
            return
        }
        deliver(text)
    }

    /** One line per final result. Never the transcript itself. */
    private fun logHeard(text: String, confidence: Float) {
        val audio = pendingPcm
        Log.i(
            VOICE,
            "stt heard chars=${text.length} conf=$confidence arabic=${ArabicRecheckPolicy.hasArabic(text)} " +
                "pcm=${audio.size} pcmRms=${rmsInt(audio)} skill=${app.matchesLocalSkill(text.trim())}",
        )
    }

    private fun rmsInt(audio: ByteArray): Int = (PcmLevel.rms16(audio) * 100f).toInt()

    private fun backupReason(ran: Boolean, setting: String, audio: ByteArray): String = when {
        ran -> "empty-text"
        setting != VoiceLanguageStore.AUTO -> "setting=$setting"
        audio.size < BackupSttPolicy.MIN_PCM_BYTES -> "pcm-short"
        rmsInt(audio) < BackupSttPolicy.MIN_PCM_RMS -> "pcm-quiet"
        else -> "cloud-unavailable"
    }

    private fun recheckReason(
        setting: String,
        whisperReady: Boolean,
        pcmBytes: Int,
        text: String,
        matchesSkill: Boolean,
    ): String = when {
        setting != VoiceLanguageStore.AUTO -> "setting=$setting"
        !whisperReady -> "whisper-not-ready"
        pcmBytes < ArabicRecheckPolicy.MIN_PCM_BYTES -> "pcm-short"
        ArabicRecheckPolicy.hasArabic(text) -> "arabic-text"
        matchesSkill && (heardConfidence < 0f || heardConfidence >= ArabicRecheckPolicy.LOW_CONFIDENCE) ->
            "confident-skill"
        else -> "soft-transcript"
    }

    /**
     * Arabic speech mis-recognized as English gets one Whisper pass before the router
     * runs. The transcript itself is never logged.
     */
    private fun recheckTranscript(text: String, confidence: Float, audio: ByteArray) {
        val gen = generation
        routeJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val whisperText = try {
                withTimeoutOrNull(3_500L) { app.whisper.transcribe(audio) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }?.trim()?.ifBlank { null }
            Log.i(VOICE, "whisper fallback result chars=${whisperText?.length ?: 0} arabic=${ArabicRecheckPolicy.hasArabic(whisperText)}")
            val chosen = ArabicRecheckPolicy.chooseText(text, whisperText)
            Log.i(
                VOICE,
                "stt recheck used=${if (ArabicRecheckPolicy.hasArabic(whisperText)) "whisper" else "recognizer"}" +
                    " conf=$confidence ms=${System.currentTimeMillis() - startedAt}" +
                    " arabic=${ArabicRecheckPolicy.hasArabic(chosen)}",
            )
            if (!isActive || gen != generation) return@launch
            deliver(chosen)
        }
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
            Log.i(VOICE, "whisper fallback result chars=${text.length} arabic=${ArabicRecheckPolicy.hasArabic(text.ifEmpty { null })}")
            if (text.isEmpty()) {
                wake.emptyTranscript()
                showIdle(sttFailure.ifBlank { "I didn't hear anything." })
                return@launch
            }
            wake.commandHeard()
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
                            wake.speak()
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
                            wake.listen()
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
        wake.speak()
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
                    wake.ttsDone(followUp = false)
                    resumeWakePassive("tts done")
                } else if (!holding && !resumeMic && !bargeStop && handsFree) {
                    wake.ttsDone(followUp = true)
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
        stopOwnCapture()
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
        wake.listen()
        WakeLog.state("LISTENING")
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
                if (gen == generation) {
                    recognizerBusy = false
                    if (!holding) resumeWakePassive("listen cancelled")
                }
                return@launch
            }
            duck.acquireIfPlaying()
            if (playChime) withContext(Dispatchers.IO) { chime.playAndWait() }
            if (gen != generation || holding) return@launch
            if (ownCaptureMode()) {
                ownCaptureHandsFree(gen, followUp = false)
            } else {
                listenHandsFree(gen, followUp = false)
            }
        }
    }

    private fun startFollowUp(gen: Int) {
        if (gen != generation || holding || !handsFree) return
        wake.listen()
        WakeLog.state("LISTENING")
        ListenForegroundService.note(getApplication(), "Listening")
        pushLevel(0f)
        _ui.update { it.copy(orb = OrbState.LISTENING, level = 0f, you = "") }
        if (ownCaptureMode()) {
            ownCaptureHandsFree(gen, followUp = true)
        } else {
            listenHandsFree(gen, followUp = true)
        }
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
            onFinal = { text, confidence ->
                if (id != listenId || gen != generation || holding) return@Callbacks
                logHeard(text, confidence)
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
            if (followUp) wake.followUpEnded() else wake.emptyTranscript()
            val message = when {
                quiet -> ""
                hard && sttFailure.isNotBlank() -> sttFailure
                else -> sttFailure.ifBlank { "I didn't hear anything." }
            }
            endHandsFree(quiet = quiet, message = message)
            return
        }
        wake.commandHeard()
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

    /** ar/auto never take SpeechRecognizer as the primary transcript; Jarvis owns the mic. */
    private fun ownCaptureMode(): Boolean =
        VoiceLanguageStore.get(getApplication()) != VoiceLanguageStore.EN

    private fun ownModeName(): String =
        if (VoiceLanguageStore.get(getApplication()) == VoiceLanguageStore.AR) "ar" else "auto"

    /** Close a running own-capture recorder so a newer turn can open the mic. */
    private fun stopOwnCapture() {
        val mic = ownMic
        ownMic = null
        ownCaptureActive = false
        mic?.stop()
    }

    /**
     * Tap path, ar/auto: one own-capture turn from hold start to after release.
     * The wake recorder was paused before this runs; the endpointer ends the
     * utterance by silence, and the finger-up stops the recorder.
     */
    private fun ownCaptureHold(gen: Int) {
        startOwnCapture()
        val mic = ownMic ?: return
        routeJob = viewModelScope.launch(Dispatchers.IO) {
            val recording = mic.capture { level -> if (holding) pushLevel(level) }
            logOwnCaptureEnd(recording)
            ownCaptureActive = false
            if (ownMic === mic) ownMic = null
            // The endpointer may finish while the finger is still down; route on release.
            while (holding && gen == generation && isActive) delay(40)
            if (gen != generation || !isActive) return@launch
            routeOwnRecording(recording, gen, handsFreeTurn = false, followUp = false)
        }
    }

    /**
     * Hands-free path, ar/auto: wake handover or follow-up. Same recording as the
     * tap path; the turn settles back to passive exactly like [listenHandsFree].
     */
    private fun ownCaptureHandsFree(gen: Int, followUp: Boolean) {
        val id = listenId
        startOwnCapture()
        val mic = ownMic ?: return
        routeJob = viewModelScope.launch(Dispatchers.IO) {
            val recording = try {
                mic.capture { level ->
                    if (id == listenId && _ui.value.orb == OrbState.LISTENING) pushLevel(level)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                com.jarvis.audio.UtteranceMic.Recording(ByteArray(0), "mic", 0)
            }
            logOwnCaptureEnd(recording)
            ownCaptureActive = false
            if (ownMic === mic) ownMic = null
            if (gen != generation || !isActive) return@launch
            recognizerBusy = false
            routeOwnRecording(recording, gen, handsFreeTurn = true, followUp = followUp)
        }
    }

    private fun startOwnCapture() {
        Log.i(VOICE, "stt own-capture start mode=${ownModeName()}")
        ownCaptureActive = true
        ownMic = UtteranceMic(getApplication())
    }

    private fun logOwnCaptureEnd(recording: UtteranceMic.Recording) {
        Log.i(
            VOICE,
            "stt own-capture end pcm=${recording.pcm.size} pcmRms=${rmsInt(recording.pcm)} " +
                "ms=${recording.ms} reason=${recording.reason} " +
                "peak=${levelInt(recording.peak)} floor=${levelInt(recording.floor)}",
        )
    }

    /** Endpointer level (PcmLevel.rms16 units) as an int x100; -1 when unknown. */
    private fun levelInt(level: Float): Int = if (level < 0f) -1 else (level * 100f).toInt()

    private fun routeOwnRecording(
        recording: UtteranceMic.Recording,
        gen: Int,
        handsFreeTurn: Boolean,
        followUp: Boolean,
    ) {
        val usable = recording.reason != "mic" && recording.reason != "no-speech" &&
            recording.pcm.size >= BackupSttPolicy.MIN_PCM_BYTES
        if (!usable) {
            finishOwnEmpty(handsFreeTurn, followUp)
            return
        }
        routed = true
        waitForResult?.cancel()
        viewModelScope.launch {
            whisperPrimary(recording.pcm, gen, handsFreeTurn = handsFreeTurn, followUp = followUp)
        }
    }

    /** An own-capture turn with no usable audio ends like today's empty transcript. */
    private fun finishOwnEmpty(handsFreeTurn: Boolean, followUp: Boolean) {
        if (!handsFreeTurn) {
            wake.emptyTranscript()
            showIdle("I didn't hear anything.")
            return
        }
        if (followUp) wake.followUpEnded() else wake.emptyTranscript()
        endHandsFree(quiet = true)
    }

    /**
     * Whisper-primary pipeline for own-capture turns (ar/auto): the language form
     * field per mode, verbose_json statistics through [WhisperAcceptPolicy], a
     * retry line for rejected transcripts, routing for accepted ones.
     */
    private suspend fun whisperPrimary(
        pcm: ByteArray,
        gen: Int,
        handsFreeTurn: Boolean,
        followUp: Boolean = false,
        debug: Boolean = false,
    ) {
        val setting = VoiceLanguageStore.get(getApplication())
        val pinnedArabic = setting == VoiceLanguageStore.AR
        var timedOut = false
        val result = try {
            withTimeoutOrNull(WHISPER_TIMEOUT_MS) {
                app.whisper.transcribePrimary(pcm, if (pinnedArabic) "ar" else null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: kotlin.run {
            timedOut = true
            null
        }
        val ok = result as? GroqTranscript.Ok
        val decision = ok?.let { WhisperAcceptPolicy.evaluate(it.text, it.noSpeechProb, it.avgLogprob) }
        logWhisperOutcome(ok, decision, timedOut)
        if (!debug && (!coroutineContext.isActive || gen != generation)) return
        if (ok == null) {
            if (!debug) finishOwnEmpty(handsFreeTurn, followUp)
            return
        }
        if (decision?.accepted == true) {
            val text = ok.text.trim()
            if (debug) {
                onRecognizedUtterance(text, generation, handsFreeTurn = false)
            } else {
                wake.commandHeard()
                if (handsFreeTurn) {
                    WakeLog.state("THINKING")
                    pushLevel(0f)
                    _ui.update {
                        it.copy(
                            orb = OrbState.THINKING,
                            level = 0f,
                            you = text,
                            holding = false,
                            brain = "",
                            accent = BrainAccent.PHONE,
                        )
                    }
                }
                deliver(text, handsFreeTurn = handsFreeTurn)
            }
        } else {
            val detectedArabic = ok.language?.lowercase()?.startsWith("ar") == true ||
                ArabicRecheckPolicy.hasArabic(ok.text)
            speakOwnRetry(pinnedArabic || detectedArabic)
        }
    }

    /** One line per Whisper verdict. Never the transcript. */
    private fun logWhisperOutcome(
        ok: GroqTranscript.Ok?,
        decision: WhisperAcceptPolicy.Decision?,
        timedOut: Boolean,
    ) {
        val reason = when {
            decision != null -> decision.reason
            timedOut -> "timeout"
            else -> "failed"
        }
        Log.i(
            VOICE,
            "stt whisper chars=${ok?.text?.length ?: 0} lang=${ok?.language ?: "-"} " +
                "conf=${String.format(Locale.US, "%.2f", ok?.confidence ?: -1f)} " +
                "accepted=${decision?.accepted ?: false} reason=$reason",
        )
    }

    /** Rejected transcript: end the turn with the retry line in the right language. */
    private suspend fun speakOwnRetry(arabic: Boolean) {
        duck.release()
        if (!holding) resumeWakePassive("own retry")
        speakQueued(
            Spoken(
                if (arabic) RETRY_ARABIC else RETRY_ENGLISH,
                BrainAccent.PHONE,
                "Phone",
                "phone",
            ),
            handsFreeTurn = false,
        )
    }

    /** DEBUG_STT_FILE entry: the same Whisper-primary pipeline, pcm already captured. */
    private suspend fun debugWhisperPrimary(pcm: ByteArray) {
        Log.i(VOICE, "debug stt pipeline pcm=${pcm.size}")
        if (pcm.size < BackupSttPolicy.MIN_PCM_BYTES) {
            Log.i(VOICE, "stt whisper chars=0 lang=- conf=-1.0 accepted=false reason=pcm-short")
            return
        }
        whisperPrimary(pcm, generation, handsFreeTurn = false, debug = true)
    }

    /** DEBUG_LISTEN: one real-mic listen turn exactly like a tap or wake handover. */
    private fun debugListen() {
        val gen = ++generation
        handsFree = true
        bargeStop = false
        bargeSession++
        listenId++
        recognizer.discard()
        speaker.stop()
        stopOwnCapture()
        abandonLive()
        utteranceJob?.cancel()
        routeJob?.cancel()
        waitForResult?.cancel()
        armCommandListen(gen, playChime = true)
    }

    private fun endHandsFree(quiet: Boolean, message: String = "") {
        if (holding) return
        bargeStop = false
        duck.release()
        resumeWakePassive("hands-free end")
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
                onFinal = { text, confidence ->
                    logHeard(text, confidence)
                    onBargeText(token, text, fromFinal = true)
                },
                onError = { _, _ -> restartBarge(token) },
                onFallbackCapture = {
                    if (token != bargeSession || holding) return@Callbacks
                    WakeLog.i("barge recognizer unavailable, model gate ${WakeConfig.TTS_THRESHOLD}")
                    wake.speak()
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
                wake.bargeStop()
                speaker.stop()
                recognizer.discard()
                stopOwnCapture()
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
                stopOwnCapture()
                utteranceJob?.cancel()
                if (!rest.isNullOrBlank()) {
                    recognizerBusy = false
                    wake.commandHeard()
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
        if (!holding) resumeWakePassive("error")
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
        if (!holding) resumeWakePassive("idle")
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
        app.onWhisperPrimaryUtterance = null
        app.onDebugListen = null
        app.onDebugTtsVoices = null
        utteranceJob?.cancel()
        stopOwnCapture()
        if (captureOpen) utteranceMic.stop()
        captureOpen = false
        abandonLive()
        duck.release()
        recognizer.release()
        speaker.shutdown()
        holding = false
        resumeWakePassive("cleared")
        super.onCleared()
    }

    private companion object {
        const val VOICE = "JarvisVoice"
        const val MAX_UTTERANCE_BYTES = 60 * 16_000 * 2
        const val LAN_REFRESH_MILLIS = 30_000L
        /** Whisper-primary ceiling; the endpointer cap is 8 s of capture anyway. */
        const val WHISPER_TIMEOUT_MS = 8_000L
        /** A rejected transcript ends the turn with this line and no answer. */
        const val RETRY_ARABIC = "عذراً، ما فهمت. عيدها مرة ثانية."
        const val RETRY_ENGLISH = "Sorry, I didn't catch that. Say it again."

        fun spokenPreview(text: String): String {
            return text.replace('\n', ' ')
                .replace(Regex("""gsk_[A-Za-z0-9_\-]+"""), "gsk_[redacted]")
                .replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
                .replace(Regex("""ya29\.[A-Za-z0-9_\-.]+"""), "ya29.[redacted]")
                .take(80)
        }
    }
}
