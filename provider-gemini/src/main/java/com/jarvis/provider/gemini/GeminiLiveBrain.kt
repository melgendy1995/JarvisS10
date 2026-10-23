@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.jarvis.provider.gemini

import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerMessage
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.asInlineDataPartOrNull
import com.google.firebase.ai.type.asTextOrNull
import com.jarvis.core.brain.Brain
import com.jarvis.core.brain.BrainAccent
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.LiveEvent
import com.jarvis.core.brain.LiveFollowUp
import com.jarvis.core.skills.SkillRegistry
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import kotlinx.coroutines.CancellationException

class GeminiLiveBrain(
    private val scope: CoroutineScope,
    private val configured: () -> Boolean,
    private val online: () -> Boolean,
    private val cooldown: GeminiCooldown,
    private val usage: UsageCounter,
    private val registry: SkillRegistry,
    private val time: TimeSource = SystemTimeSource,
) : Brain {
    override val id: String = ID
    override val accent: BrainAccent = BrainAccent.GEMINI

    @Volatile private var openSession: GeminiLiveSession? = null

    override suspend fun reply(request: BrainRequest): BrainResult {
        gate(
            id = id,
            configured = configured,
            online = online,
            cooldown = cooldown,
            time = time,
            liveCapReached = usage.liveCapReached(),
        )?.let { return it }
        openSession?.close()
        val session = GeminiLiveSession(scope, registry, time, usage)
        openSession = session
        return try {
            when (val first = session.open(request.utterance)) {
                null -> {
                    session.close()
                    BrainResult.Failure(id, BrainError.Unavailable)
                }
                else -> {
                    usage.recordLiveTurn()
                    BrainResult.Answer(
                        brainId = id,
                        accent = accent,
                        text = first.text,
                        needsPermission = first.needsPermission,
                        audioPcm24k = first.pcm.takeIf { it.isNotEmpty() },
                        followUp = if (first.keepOpen) session else null,
                    ).also {
                        if (!first.keepOpen) session.close()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            session.close()
            throw cancelled
        } catch (error: Throwable) {
            session.close()
            error.toBrainFailure(id, cooldown, time)
        }
    }

    companion object {
        const val ID = "gemini-live"
    }
}

private class FirstTurn(
    val text: String,
    val pcm: ByteArray,
    val needsPermission: String?,
    val keepOpen: Boolean,
)

private sealed interface Note {
    val generation: Int

    data class Message(override val generation: Int, val message: LiveServerMessage) : Note
    data class Ended(override val generation: Int) : Note
    data class Failed(override val generation: Int, val error: Throwable) : Note
}

private class GeminiLiveSession(
    private val scope: CoroutineScope,
    private val registry: SkillRegistry,
    private val time: TimeSource,
    private val usage: UsageCounter,
) : LiveFollowUp {
    override val followUpWindow: Duration = Duration.ofMillis(ModelConfig.FOLLOW_UP_WINDOW_MS)
    private val followEvents = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveEvent> = followEvents.receiveAsFlow()

    private val mutex = Mutex()
    private val notes = Channel<Note>(Channel.UNLIMITED)
    private val intentional = AtomicBoolean(false)
    private val secondsRecorded = AtomicBoolean(false)
    private var session: LiveSession? = null
    private var handle: String? = null
    private var generation = 0
    private var reconnects = 0
    private var connected = false
    private var receiveJob: Job? = null
    private var driveJob: Job? = null
    private val startedAt = time.now()

    suspend fun open(utterance: String): FirstTurn? {
        val deferred = CompletableDeferred<FirstTurn?>()
        driveJob = scope.launch {
            try {
                drive(utterance, deferred)
            } catch (cancelled: CancellationException) {
                if (!deferred.isCompleted) deferred.cancel(cancelled)
            } catch (error: Throwable) {
                if (!deferred.isCompleted) deferred.completeExceptionally(error)
                else Log.w(TAG, "live follow-up failed: ${error.javaClass.simpleName}")
            }
        }
        return deferred.await()
    }

    override suspend fun sendMicPcm16k(frame: ByteArray) {
        if (intentional.get() || frame.isEmpty()) return
        val current = mutex.withLock { session } ?: return
        runCatching { current.sendAudioRealtime(InlineData(frame, "audio/pcm")) }
    }

    override suspend fun close() {
        if (!intentional.compareAndSet(false, true)) return
        generation++
        receiveJob?.cancel()
        driveJob?.cancel()
        val current = mutex.withLock { session.also { session = null } }
        if (current != null) {
            runCatching { current.stopReceiving() }
            runCatching { current.close() }
        }
        followEvents.trySend(LiveEvent.Closed)
        followEvents.close()
        notes.close()
        recordSeconds()
    }

    private fun recordSeconds() {
        if (!connected || !secondsRecorded.compareAndSet(false, true)) return
        val millis = Duration.between(startedAt, time.now()).toMillis().coerceAtLeast(0)
        usage.addLiveSeconds((millis + 999) / 1000)
    }

    private suspend fun drive(utterance: String, first: CompletableDeferred<FirstTurn?>) {
        val model = GeminiModels.live(registry)
        connect(model, utterance, resend = true)
        val audio = ByteArrayOutputStream()
        val transcript = StringBuilder()
        var permission: String? = null
        var saw = false
        var firstDeadline = System.nanoTime() + FIRST_WAIT_NS
        var turnDeadline = System.nanoTime() + TURN_WAIT_NS
        while (!intentional.get()) {
            val deadline = if (saw) turnDeadline else firstDeadline
            val waitMs = (deadline - System.nanoTime()) / 1_000_000L
            if (waitMs <= 0L) {
                if (!saw && reconnect()) {
                    connect(model, utterance, resend = true)
                    firstDeadline = System.nanoTime() + FIRST_WAIT_NS
                    turnDeadline = System.nanoTime() + TURN_WAIT_NS
                    continue
                }
                break
            }
            val note = withTimeoutOrNull(waitMs) { notes.receive() } ?: continue
            if (note.generation != generation) continue
            when (note) {
                is Note.Ended -> {
                    if (!reconnect()) break
                    connect(model, utterance, resend = handle == null)
                    firstDeadline = System.nanoTime() + FIRST_WAIT_NS
                    turnDeadline = System.nanoTime() + TURN_WAIT_NS
                }
                is Note.Failed -> {
                    if (QuotaSignals.isRateLimit(note.error) || QuotaSignals.isNotConfigured(note.error)) {
                        throw note.error
                    }
                    if (!reconnect()) break
                    connect(model, utterance, resend = handle == null)
                    firstDeadline = System.nanoTime() + FIRST_WAIT_NS
                    turnDeadline = System.nanoTime() + TURN_WAIT_NS
                }
                is Note.Message -> {
                    when (val message = note.message) {
                        is LiveServerGoAway -> {
                            if (reconnect()) {
                                connect(model, utterance, resend = handle == null && !saw)
                                firstDeadline = System.nanoTime() + FIRST_WAIT_NS
                                turnDeadline = System.nanoTime() + TURN_WAIT_NS
                            }
                        }
                        is LiveSessionResumptionUpdate -> message.newHandle?.let { handle = it }
                        is LiveServerToolCall -> {
                            saw = true
                            turnDeadline = System.nanoTime() + TURN_WAIT_NS
                            permission = runTools(message, utterance, transcript) ?: permission
                        }
                        is LiveServerContent -> {
                            if (message.content != null || message.outputTranscription != null) saw = true
                            absorb(message, audio, transcript, emit = false)
                            if (message.interrupted) saw = true
                            if (message.turnComplete || message.generationComplete) {
                                val turn = finishTurn(audio, transcript, permission, keepOpen = true)
                                first.complete(turn)
                                if (turn?.keepOpen == true) pumpFollowUp(model, utterance)
                                return
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        if (!first.isCompleted) {
            first.complete(finishTurn(audio, transcript, permission, keepOpen = false))
        }
    }

    private suspend fun pumpFollowUp(model: LiveGenerativeModel, utterance: String) {
        val transcript = StringBuilder()
        while (!intentional.get()) {
            val note = notes.receiveCatching().getOrNull() ?: break
            if (note.generation != generation) continue
            when (note) {
                is Note.Ended -> {
                    if (!reconnect()) {
                        followEvents.trySend(LiveEvent.Closed)
                        break
                    }
                    connect(model, utterance, resend = false)
                }
                is Note.Failed -> {
                    if (QuotaSignals.isRateLimit(note.error)) throw note.error
                    if (!reconnect()) {
                        followEvents.trySend(LiveEvent.Closed)
                        break
                    }
                    connect(model, utterance, resend = false)
                }
                is Note.Message -> when (val message = note.message) {
                    is LiveServerGoAway -> {
                        if (reconnect()) connect(model, utterance, resend = false)
                    }
                    is LiveSessionResumptionUpdate -> message.newHandle?.let { handle = it }
                    is LiveServerToolCall -> {
                        val permission = runTools(message, utterance, transcript)
                        if (permission != null) followEvents.trySend(LiveEvent.NeedsPermission(permission))
                    }
                    is LiveServerContent -> {
                        absorb(message, audio = null, transcript = transcript, emit = true)
                        if (message.turnComplete || message.generationComplete || message.interrupted) {
                            val text = transcript.toString()
                            if (text.isNotBlank()) followEvents.trySend(LiveEvent.Transcript(text))
                            transcript.clear()
                            followEvents.trySend(LiveEvent.TurnComplete)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun runTools(
        call: LiveServerToolCall,
        utterance: String,
        transcript: StringBuilder,
    ): String? {
        val current = mutex.withLock { session } ?: return null
        var permission: String? = null
        val parts = call.functionCalls.map { function ->
            val outcome = SkillTools.respond(registry, utterance, function)
            Transcripts.append(transcript, outcome.spoken)
            if (permission == null) permission = outcome.needsPermission
            outcome.part
        }
        if (parts.isNotEmpty()) {
            runCatching { current.sendFunctionResponse(parts) }
        }
        return permission
    }

    private fun absorb(
        message: LiveServerContent,
        audio: ByteArrayOutputStream?,
        transcript: StringBuilder,
        emit: Boolean,
    ) {
        Transcripts.append(transcript, message.outputTranscription?.text)
        for (part in message.content?.parts.orEmpty()) {
            val inline = part.asInlineDataPartOrNull()
            if (inline != null && inline.mimeType.startsWith("audio")) {
                val bytes = inline.inlineData
                audio?.write(bytes)
                if (emit && bytes.isNotEmpty()) followEvents.trySend(LiveEvent.Audio(bytes.copyOf()))
            } else {
                Transcripts.append(transcript, part.asTextOrNull())
            }
        }
    }

    private fun finishTurn(
        audio: ByteArrayOutputStream,
        transcript: StringBuilder,
        permission: String?,
        keepOpen: Boolean,
    ): FirstTurn? {
        val pcm = audio.toByteArray()
        val text = transcript.toString().trim()
        if (pcm.isEmpty() && text.isEmpty()) return null
        return FirstTurn(text, pcm, permission, keepOpen && !intentional.get())
    }

    private fun reconnect(): Boolean {
        val allowed = LiveReconnect.allow(reconnects > 0, intentional.get())
        if (allowed) reconnects++
        return allowed
    }

    private suspend fun connect(model: LiveGenerativeModel, utterance: String, resend: Boolean) {
        val previous = mutex.withLock { session.also { session = null } }
        val gen = ++generation
        receiveJob?.cancel()
        if (previous != null) {
            runCatching { previous.stopReceiving() }
            runCatching { previous.close() }
        }
        val opened = openSocket(model)
        connected = true
        mutex.withLock { session = opened }
        receiveJob = scope.launch {
            try {
                opened.receive().collect { message ->
                    if (gen == generation && !intentional.get()) notes.send(Note.Message(gen, message))
                }
                if (gen == generation && !intentional.get()) notes.send(Note.Ended(gen))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (gen == generation && !intentional.get()) notes.send(Note.Failed(gen, error))
            }
        }
        if (resend) opened.send(utterance)
    }

    private suspend fun openSocket(model: LiveGenerativeModel): LiveSession {
        val resume = handle?.let { SessionResumptionConfig(it) } ?: SessionResumptionConfig()
        return try {
            model.connect(resume)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (QuotaSignals.isRateLimit(error) || QuotaSignals.isNotConfigured(error)) throw error
            Log.w(TAG, "live connect with resumption failed: ${error.javaClass.simpleName}")
            handle = null
            model.connect()
        }
    }

    private companion object {
        const val FIRST_WAIT_NS = 8_000_000_000L
        const val TURN_WAIT_NS = 20_000_000_000L
    }
}
