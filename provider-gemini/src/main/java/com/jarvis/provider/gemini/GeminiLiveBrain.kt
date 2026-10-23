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
import com.jarvis.core.brain.PromptFitter
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
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
    private val circuit: LiveCircuit = LiveCircuit(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Brain {
    override val id: String = ID
    override val accent: BrainAccent = BrainAccent.GEMINI

    @Volatile private var openSession: GeminiLiveSession? = null
    @Volatile private var lastModelRejected: Boolean = false

    override suspend fun reply(request: BrainRequest): BrainResult {
        return try {
            replyGated(request)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            openSession?.close()
            Log.w(TAG, "live reply cancelled ${cancelled.causeChain()}", cancelled)
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (error: Throwable) {
            openSession?.close()
            error.toBrainFailure(id, cooldown, time)
        }
    }

    private suspend fun replyGated(request: BrainRequest): BrainResult {
        gate(
            id = id,
            configured = configured,
            online = online,
            cooldown = cooldown,
            time = time,
            liveCapReached = usage.liveCapReached(),
        )?.let { return it }
        if (circuit.skip(nowMs())) {
            Log.i(TAG, "live skipped (recent failure)")
            return BrainResult.Failure(id, BrainError.Unavailable)
        }
        val models = listOf(ModelConfig.LIVE_MODEL, ModelConfig.LIVE_MODEL_FALLBACK).distinct()
        var last = BrainResult.Failure(id, BrainError.Unavailable)
        for ((index, model) in models.withIndex()) {
            val outcome = oneSession(request, model)
            if (outcome is BrainResult.Answer) {
                circuit.clear()
                return outcome
            }
            last = outcome as BrainResult.Failure
            val rejected = lastModelRejected
            lastModelRejected = false
            val blocked = last.error is BrainError.RateLimited || last.error == BrainError.NotConfigured
            if (blocked) return last
            if (!rejected || index == models.lastIndex) {
                circuit.mark(nowMs())
                return last
            }
            Log.w(TAG, "live model rejected model=$model fallback=${models[index + 1]}")
        }
        circuit.mark(nowMs())
        return last
    }

    private suspend fun oneSession(request: BrainRequest, modelName: String): BrainResult {
        Log.i(
            TAG,
            "live begin model=$modelName backend=${GeminiModels.BACKEND} modality=AUDIO voice=${ModelConfig.LIVE_VOICE}",
        )
        openSession?.close()
        val system = PromptFitter().memorySystem(
            baseSystem = ModelConfig.SYSTEM_PROMPT,
            persona = request.persona,
            facts = request.facts,
            summary = request.summary,
            priorTurns = request.turns,
            charBudget = PromptFitter.LIVE_CHAR_BUDGET,
        )
        val session = GeminiLiveSession(scope, registry, time, usage, system, modelName)
        openSession = session
        return try {
            val first = withTimeoutOrNull(OPEN_TIMEOUT_MS) { session.open(request.utterance) }
            when (first) {
                null -> {
                    noteRejection(session)
                    val detail = session.lastDetail
                    session.close()
                    Log.w(TAG, "live open ended without an answer model=$modelName $detail")
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
            noteRejection(session)
            val detail = session.lastDetail
            session.close()
            Log.w(TAG, "live cancelled model=$modelName $detail ${cancelled.causeChain()}", cancelled)
            if (!currentCoroutineContext().isActive) throw cancelled
            BrainResult.Failure(id, BrainError.Unavailable)
        } catch (error: Throwable) {
            noteRejection(session, error)
            val detail = session.lastDetail
            session.close()
            if (lastModelRejected) {
                Log.w(TAG, "live model not found model=$modelName $detail ${error.causeChain()}", error)
            }
            error.toBrainFailure(id, cooldown, time)
        }
    }

    private fun noteRejection(session: GeminiLiveSession, error: Throwable? = null) {
        lastModelRejected = session.modelRejected ||
            (error?.isModelRejected() == true) ||
            IllegalStateException(session.lastDetail).isModelRejected()
    }

    companion object {
        const val ID = "gemini-live"
        private const val OPEN_TIMEOUT_MS = 20_000L
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
    private val system: String,
    private val modelName: String,
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

    @Volatile var lastDetail: String = ""
    @Volatile var modelRejected: Boolean = false

    suspend fun open(utterance: String): FirstTurn? {
        val deferred = CompletableDeferred<FirstTurn?>()
        driveJob = scope.launch {
            try {
                drive(utterance, deferred)
            } catch (cancelled: CancellationException) {
                val socket = session
                val detail = if (socket != null) closeDetailQuiet(socket) else lastDetail.ifBlank { "no session" }
                if (socket != null) lastDetail = detail
                Log.w(TAG, "live drive cancelled model=$modelName close=$detail ${cancelled.causeChain()}", cancelled)
                // Complete with a normal exception. Cancelling the deferred would
                // cancel the voice job that is waiting, and Groq would never run.
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(LiveSocketEnded(cancelled, detail))
                }
            } catch (error: Throwable) {
                if (lastDetail.isBlank()) lastDetail = error.causeChain()
                if (error.isModelRejected()) modelRejected = true
                if (!deferred.isCompleted) deferred.completeExceptionally(error)
                else Log.w(TAG, "live follow-up failed ${error.causeChain()}", error)
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
        val model = GeminiModels.live(registry, system, modelName)
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
                    if (note.error.isModelRejected()) modelRejected = true
                    Log.w(TAG, "live session failed model=$modelName ${note.error.causeChain()}", note.error)
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
                    Log.w(TAG, "live follow-up session failed model=$modelName ${note.error.causeChain()}", note.error)
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
        Log.i(TAG, "live voice requested=${ModelConfig.LIVE_VOICE} model=$modelName")
        val opened = openSocket(model)
        Log.i(
            TAG,
            "live setup acknowledged model=$modelName backend=${GeminiModels.BACKEND} modality=AUDIO voice=${ModelConfig.LIVE_VOICE}",
        )
        connected = true
        mutex.withLock { session = opened }
        receiveJob = scope.launch receiveLoop@{
            try {
                opened.receive().collect { message ->
                    if (gen == generation && !intentional.get()) notes.send(Note.Message(gen, message))
                }
                if (gen == generation && !intentional.get()) notes.send(Note.Ended(gen))
            } catch (cancelled: CancellationException) {
                if (intentional.get()) return@receiveLoop
                val detail = closeDetailQuiet(opened)
                lastDetail = detail
                val ended = LiveSocketEnded(cancelled, detail)
                if (ended.isModelRejected()) modelRejected = true
                Log.w(TAG, "live receive cancelled model=$modelName close=$detail ${cancelled.causeChain()}", cancelled)
                if (gen == generation && !intentional.get()) notes.trySend(Note.Failed(gen, ended))
            } catch (error: Throwable) {
                val detail = closeDetailQuiet(opened)
                lastDetail = detail
                if (error.isModelRejected()) modelRejected = true
                Log.w(TAG, "live receive failed model=$modelName close=$detail ${error.causeChain()}", error)
                if (gen == generation && !intentional.get()) notes.trySend(Note.Failed(gen, error))
            }
        }
        if (resend) {
            try {
                // Native-audio Live rejects clientContent with close 1007. Realtime text is
                // the supported turn. An activity-end frame by itself is also rejected.
                Log.i(TAG, "live send realtime text model=$modelName")
                opened.sendTextRealtime(utterance)
            } catch (cancelled: CancellationException) {
                val detail = closeDetailQuiet(opened)
                lastDetail = detail
                Log.w(TAG, "live send cancelled model=$modelName close=$detail ${cancelled.causeChain()}", cancelled)
                if (!currentCoroutineContext().isActive) throw cancelled
                throw LiveSocketEnded(cancelled, detail)
            } catch (error: Throwable) {
                val detail = closeDetailQuiet(opened)
                lastDetail = detail
                Log.w(TAG, "live send failed model=$modelName close=$detail ${error.causeChain()}", error)
                throw error
            }
        }
    }

    private suspend fun openSocket(model: LiveGenerativeModel): LiveSession {
        val stored = handle
        // An empty SessionResumptionConfig still serializes a resumption block. Omit it
        // until the server has given us a handle.
        val first = connectWithin(model, stored?.let { SessionResumptionConfig(it) })
        if (first != null) return first
        if (stored == null) throw IllegalStateException("live connect ended $lastDetail")
        Log.w(TAG, "live connect retry without resumption model=$modelName")
        handle = null
        return connectWithin(model, null) ?: throw IllegalStateException("live connect ended $lastDetail")
    }

    private suspend fun connectWithin(
        model: LiveGenerativeModel,
        resume: SessionResumptionConfig?,
    ): LiveSession? {
        try {
            // The websocket actor is a child of connect(). A server close cancels that
            // child. supervisorScope keeps the voice job alive so text and Groq still run.
            val opened = supervisorScope {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    if (resume == null) model.connect() else model.connect(resume)
                }
            }
            if (opened == null) {
                lastDetail = "connect timeout"
                Log.w(TAG, "live connect timed out model=$modelName")
            }
            return opened
        } catch (cancelled: CancellationException) {
            val detail = "connect cancelled ${cancelled.causeChain()}"
            lastDetail = detail
            Log.w(TAG, "live connect cancelled model=$modelName $detail", cancelled)
            if (!currentCoroutineContext().isActive) throw cancelled
            return null
        } catch (error: Throwable) {
            lastDetail = error.causeChain()
            if (error.isModelRejected()) modelRejected = true
            if (QuotaSignals.isRateLimit(error) || QuotaSignals.isNotConfigured(error)) throw error
            Log.w(TAG, "live connect failed model=$modelName ${error.causeChain()}", error)
            return null
        }
    }

    private companion object {
        const val FIRST_WAIT_NS = 8_000_000_000L
        const val TURN_WAIT_NS = 20_000_000_000L
        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}

/** A socket closed itself. This is not a cancelled question. */
private class LiveSocketEnded(
    cause: Throwable,
    val detail: String,
) : Exception("live socket ended $detail", cause)

private suspend fun closeDetailQuiet(session: LiveSession?): String {
    return try {
        liveCloseDetail(session)
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) "close skipped"
        else "close reason cancelled ${cancelled.javaClass.simpleName}"
    } catch (error: Throwable) {
        "close unread ${error.javaClass.simpleName}"
    }
}

/**
 * Firebase AI keeps the ktor socket private. Read the close code before the session
 * is closed, without taking a compile dependency on ktor.
 */
private suspend fun liveCloseDetail(session: LiveSession?): String {
    if (session == null) return "no session"
    return try {
        val liveClass = LiveSession::class.java
        val accessName = "access" + '$' + "getSession" + '$' + "p"
        val access = liveClass.getDeclaredMethod(accessName, liveClass)
        val socket = access.invoke(null, session) ?: return "no websocket"
        val getter = socket.javaClass.methods.firstOrNull { it.name == "getCloseReason" && it.parameterCount == 0 }
            ?: return "no closeReason method"
        val deferred = getter.invoke(socket) as? Deferred<*> ?: return "no closeReason"
        val reason = withTimeoutOrNull(500) {
            try {
                deferred.await()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                null
            }
        } ?: return if (deferred.isCompleted) "close reason empty" else "close reason pending"
        val codeObject = reason.javaClass.methods
            .firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
            ?.invoke(reason)
        val code = codeObject?.javaClass?.methods
            ?.firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
            ?.invoke(codeObject)
            ?: codeObject
        val message = reason.javaClass.methods
            .firstOrNull { it.name == "getMessage" && it.parameterCount == 0 }
            ?.invoke(reason) as? String
        val detail = "code=$code message=${redact(message.orEmpty())}"
        Log.w(TAG, "live close $detail")
        detail
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        "close reason cancelled"
    } catch (error: Throwable) {
        "close reason unreadable: ${error.javaClass.simpleName}"
    }
}
