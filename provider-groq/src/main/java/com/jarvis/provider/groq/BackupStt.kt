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

/**
 * A recognized-but-suspicious transcript gets one Whisper pass. On-device English
 * garble like "Shuffle" for Arabic speech is the case this guards against; Arabic
 * transcripts and solid local skill matches are left alone.
 */
object ArabicRecheckPolicy {
    const val MIN_PCM_BYTES = BackupSttPolicy.MIN_PCM_BYTES

    /** Confidence below this is a coin flip. Absent is -1 and never counts as low. */
    const val LOW_CONFIDENCE = 0.6f

    fun hasArabic(text: String?): Boolean {
        if (text == null) return false
        return text.any { it.code in 0x600..0x6FF }
    }

    fun shouldRecheck(
        whisperReady: Boolean,
        pcmBytes: Int,
        recognizerText: String,
        matchesLocalSkill: Boolean,
        confidence: Float,
    ): Boolean {
        if (!whisperReady) return false
        if (pcmBytes < MIN_PCM_BYTES) return false
        if (hasArabic(recognizerText)) return false
        if (matchesLocalSkill && (confidence < 0f || confidence >= LOW_CONFIDENCE)) return false
        return true
    }

    /** Whisper wins only when it actually heard Arabic; otherwise the recognizer text stands. */
    fun chooseText(recognizerText: String, whisperText: String?): String {
        return if (whisperText != null && hasArabic(whisperText)) whisperText else recognizerText
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
