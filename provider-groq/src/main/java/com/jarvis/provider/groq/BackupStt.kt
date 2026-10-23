package com.jarvis.provider.groq

/**
 * Whisper runs only after on-device recognition produced no text, and only when the
 * next brain is a cloud brain. The router still sees the transcript first.
 */
object BackupSttPolicy {
    /** About 0.4s of 16 kHz 16-bit mono. Shorter clips are silence, not a question. */
    const val MIN_PCM_BYTES = 12_800

    fun shouldTranscribe(
        recognizerText: String,
        recognizerFailed: Boolean,
        pcmBytes: Int,
        cloudNext: Boolean,
    ): Boolean {
        if (recognizerText.isNotBlank()) return false
        if (!recognizerFailed) return false
        if (!cloudNext) return false
        return pcmBytes >= MIN_PCM_BYTES
    }
}

class GroqWhisperStt(
    private val proxyUrl: () -> String,
    private val bearer: () -> String,
    private val transport: GroqTransport,
    private val log: (String) -> Unit = {},
) {
    fun ready(): Boolean {
        return GroqProxy.normalize(proxyUrl()) != null && bearer().trim().isNotEmpty()
    }

    suspend fun transcribe(pcm16le: ByteArray): String? {
        if (!ready() || pcm16le.size < BackupSttPolicy.MIN_PCM_BYTES) return null
        log("whisper fallback bytes=${pcm16le.size}")
        val wav = WavPcm.encode(pcm16le, GroqModels.PCM_SAMPLE_RATE)
        return when (val result = transport.transcribe(wav)) {
            is GroqTranscript.Ok -> result.text.trim().ifBlank { null }
            GroqTranscript.Failed -> null
        }
    }
}
