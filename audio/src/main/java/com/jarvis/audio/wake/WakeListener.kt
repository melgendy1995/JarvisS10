package com.jarvis.audio.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class WakeHit(val score: Float, val duringSpeech: Boolean)

/**
 * Always-on 16 kHz capture and the openWakeWord scorer.
 * [pauseBlocking] releases the recorder before SpeechRecognizer starts.
 */
class WakeListener(
    context: Context,
    private val session: WakeSession = WakeSession(),
    private val onHit: (WakeHit) -> Unit,
) {
    data class WavScore(val max: Float, val triggered: Boolean, val samples: Int)

    private val app = context.applicationContext
    private val running = AtomicBoolean(false)
    private val monitor = Object()
    private val sessionLock = Object()
    private var thread: Thread? = null
    private var nets: OnnxWakeNets? = null
    private var pipeline: WakePipeline? = null
    private var scorer = WakeScorer()
    @Volatile private var owner = Owner.BUSY
    @Volatile private var speaking = false
    @Volatile private var micHeld = false
    private val pauseEpoch = AtomicInteger(0)
    private val parkedEpoch = AtomicInteger(0)
    private val restartEpoch = AtomicInteger(0)
    private val watchdogHold = AtomicInteger(0)
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastWatchdogAt = 0L
    @Volatile private var failed = false
    private var hot = 0

    val lastFrameAtElapsedRealtime: Long
        get() = lastFrameAt

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastFrameAt = SystemClock.elapsedRealtime()
        val worker = Thread({ runLoop() }, "jarvis-wake")
        thread = worker
        worker.start()
    }

    fun pauseBlocking() {
        val epoch = pauseEpoch.incrementAndGet()
        owner = Owner.BUSY
        speaking = false
        synchronized(monitor) { monitor.notifyAll() }
        val until = SystemClock.elapsedRealtime() + 700
        while ((parkedEpoch.get() < epoch || micHeld) && SystemClock.elapsedRealtime() < until) {
            Thread.sleep(15)
        }
        if (parkedEpoch.get() < epoch || micHeld) Log.w(WakeConfig.LOG_TAG, "mic release timed out")
        else Log.i(WakeConfig.LOG_TAG, "mic closed")
    }

    fun resume(speaking: Boolean) {
        this.speaking = speaking
        owner = Owner.WAKE
        lastFrameAt = SystemClock.elapsedRealtime()
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun restartCapture() {
        restartEpoch.incrementAndGet()
        if (session.shouldCapture && owner != Owner.WAKE) {
            speaking = false
            owner = Owner.WAKE
        }
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun scoreWav(samples: ShortArray): WavScore {
        val restore = owner == Owner.WAKE
        val wasSpeaking = speaking
        watchdogHold.incrementAndGet()
        pauseBlocking()
        try {
            val model = nets ?: return WavScore(0f, false, samples.size).also {
                Log.w(WakeConfig.LOG_TAG, "debug wav skipped, models are not loaded")
            }
            val pipe = synchronized(sessionLock) { WakePipeline(model, prime = true) }
            val gate = WakeScorer()
            val padded = ShortArray(WakeConfig.SAMPLE_RATE + samples.size + WakeConfig.SAMPLE_RATE)
            samples.copyInto(padded, WakeConfig.SAMPLE_RATE)
            var max = 0f
            var triggered = false
            var offset = 0
            while (offset + WakeConfig.FRAME_SAMPLES <= padded.size) {
                val frame = padded.copyOfRange(offset, offset + WakeConfig.FRAME_SAMPLES)
                offset += WakeConfig.FRAME_SAMPLES
                val raw = synchronized(sessionLock) { pipe.accept(frame) } ?: continue
                if (raw > max) max = raw
                val accepted = gate.accept(raw, WakeConfig.THRESHOLD)
                if (accepted > 0f) triggered = true
            }
            Log.i(
                WakeConfig.LOG_TAG,
                "debug wav samples=${samples.size} max=${fmt(max)} triggered=$triggered threshold=${fmt(WakeConfig.THRESHOLD)}",
            )
            return WavScore(max, triggered, samples.size)
        } finally {
            watchdogHold.decrementAndGet()
            if (restore) resume(wasSpeaking)
        }
    }

    fun close() {
        running.set(false)
        owner = Owner.BUSY
        synchronized(monitor) { monitor.notifyAll() }
        thread?.join(800)
        thread = null
        synchronized(sessionLock) {
            nets?.close()
            nets = null
            pipeline = null
        }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val loaded = runCatching { OnnxWakeNets(app) }
        if (loaded.isFailure) {
            failed = true
            running.set(false)
            Log.w(WakeConfig.LOG_TAG, "model load failed ${loaded.exceptionOrNull()?.javaClass?.simpleName}")
            return
        }
        val model = loaded.getOrThrow()
        synchronized(sessionLock) {
            nets = model
            pipeline = WakePipeline(model, prime = true)
        }
        Log.i(WakeConfig.LOG_TAG, "pipeline ready plainJarvis=${WakeConfig.PLAIN_JARVIS_MODEL}")
        owner = Owner.WAKE
        lastFrameAt = SystemClock.elapsedRealtime()
        var record: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        val pending = ShortArray(WakeConfig.FRAME_SAMPLES)
        var filled = 0
        var frames = 0L
        var framesSinceOpen = 0L
        var seenRestart = restartEpoch.get()
        var flushOnResume = false
        try {
            while (running.get()) {
                val now = SystemClock.elapsedRealtime()
                val kick = restartEpoch.get()
                if (maybeWatchdog(now) || kick != seenRestart) {
                    seenRestart = restartEpoch.get()
                    filled = 0
                    flushOnResume = true
                    release(record, echo, noise)
                    record = null
                    echo = null
                    noise = null
                    micHeld = false
                }
                if (owner != Owner.WAKE) {
                    filled = 0
                    flushOnResume = true
                    release(record, echo, noise)
                    record = null
                    echo = null
                    noise = null
                    micHeld = false
                    synchronized(monitor) {
                        while (running.get() && owner != Owner.WAKE) {
                            parkedEpoch.set(pauseEpoch.get())
                            maybeWatchdog(SystemClock.elapsedRealtime())
                            if (owner == Owner.WAKE) break
                            monitor.wait(400)
                        }
                    }
                    continue
                }
                if (record == null) {
                    val opened = openRecord()
                    record = opened?.record
                    echo = opened?.echo
                    noise = opened?.noise
                    if (record == null) {
                        Thread.sleep(500)
                        continue
                    }
                    micHeld = true
                    framesSinceOpen = 0
                    lastFrameAt = SystemClock.elapsedRealtime()
                    Log.i(
                        WakeConfig.LOG_TAG,
                        "mic open source=${opened?.source} aec=${echo?.enabled == true} ns=${noise?.enabled == true} speaking=$speaking",
                    )
                    if (flushOnResume) {
                        flushOnResume = false
                        flush()
                    }
                }
                val current = record ?: continue
                val chunk = ShortArray(WakeConfig.FRAME_SAMPLES - filled)
                val read = current.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    Log.w(WakeConfig.LOG_TAG, "mic read error $read")
                    release(record, echo, noise)
                    record = null
                    echo = null
                    noise = null
                    micHeld = false
                    continue
                }
                if (read == 0) continue
                chunk.copyInto(pending, filled, 0, read)
                filled += read
                if (filled < WakeConfig.FRAME_SAMPLES) continue
                val frame = pending.copyOf()
                filled = 0
                if (owner != Owner.WAKE) continue
                val started = SystemClock.elapsedRealtime()
                val raw = synchronized(sessionLock) { pipeline?.accept(frame) } ?: continue
                frames++
                framesSinceOpen++
                lastFrameAt = SystemClock.elapsedRealtime()
                val state = if (speaking) "SPEAKING" else "PASSIVE"
                val gate = if (speaking) WakeConfig.TTS_THRESHOLD else WakeConfig.THRESHOLD
                val judged = if (speaking) {
                    if (raw >= gate) hot++ else hot = 0
                    if (hot >= WakeConfig.TTS_PATIENCE) raw else 0f
                } else {
                    hot = 0
                    raw
                }
                val accepted = scorer.accept(judged, gate, allowSoft = !speaking)
                if (raw >= WakeConfig.LOG_SCORE) {
                    Log.i(
                        WakeConfig.LOG_TAG,
                        "score hey_jarvis=${fmt(raw)} threshold=${fmt(gate)} state=$state",
                    )
                }
                if (accepted > 0f) {
                    Log.i(WakeConfig.LOG_TAG, "trigger hey_jarvis=${fmt(accepted)} threshold=${fmt(gate)} state=$state")
                    onHit(WakeHit(accepted, speaking))
                }
                if (framesSinceOpen == 1L || frames % 200L == 0L) {
                    Log.i(WakeConfig.LOG_TAG, "frame ${SystemClock.elapsedRealtime() - started}ms count=$frames state=$state")
                }
            }
        } catch (error: Exception) {
            Log.w(WakeConfig.LOG_TAG, "listener stopped ${error.javaClass.simpleName}")
        } finally {
            release(record, echo, noise)
            micHeld = false
        }
    }

    private fun maybeWatchdog(now: Long): Boolean {
        if (watchdogHold.get() > 0) return false
        if (!session.shouldCapture) return false
        if (!session.watchdogShouldRestart(now, lastFrameAt)) return false
        if (now - lastWatchdogAt < WakeConfig.WATCHDOG_MS) return false
        lastWatchdogAt = now
        Log.i(WakeConfig.LOG_TAG, "wake watchdog restart")
        restartCapture()
        return true
    }

    private fun flush() {
        val pipe = pipeline ?: return
        val zeros = ShortArray(WakeConfig.FRAME_SAMPLES)
        synchronized(sessionLock) {
            repeat(WakeConfig.WAKE_FRAMES) { pipe.accept(zeros) }
        }
        scorer.reset()
        hot = 0
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): Opened? {
        val min = AudioRecord.getMinBufferSize(
            WakeConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        val bytes = maxOf(min, WakeConfig.FRAME_SAMPLES * 2 * 4)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val created = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(WakeConfig.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bytes)
                    .build()
            } catch (error: Exception) {
                Log.w(WakeConfig.LOG_TAG, "mic source $source rejected ${error.javaClass.simpleName}")
                continue
            }
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                continue
            }
            val echo = if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(created.audioSessionId)?.also { it.enabled = true }
            } else {
                null
            }
            val noise = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(created.audioSessionId)?.also { it.enabled = true }
            } else {
                null
            }
            val started = runCatching { created.startRecording() }.isSuccess &&
                created.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (!started) {
                echo?.release()
                noise?.release()
                created.release()
                continue
            }
            return Opened(created, echo, noise, source)
        }
        Log.w(WakeConfig.LOG_TAG, "mic open failed")
        return null
    }

    private fun release(record: AudioRecord?, echo: AcousticEchoCanceler?, noise: NoiseSuppressor?) {
        runCatching { record?.stop() }
        echo?.release()
        noise?.release()
        record?.release()
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value)

    private enum class Owner { WAKE, BUSY }

    private class Opened(
        val record: AudioRecord,
        val echo: AcousticEchoCanceler?,
        val noise: NoiseSuppressor?,
        val source: Int,
    )
}
