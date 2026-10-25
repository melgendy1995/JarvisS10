package com.jarvis.provider.groq

/**
 * Own-capture Whisper transcripts gate the brain: silence hallucinations and
 * low-confidence verbose_json statistics are rejected so the turn ends with a
 * retry line instead of an answer to garbage. Pure, without Android deps.
 */
object WhisperAcceptPolicy {
    /** Fewer letters than this in the text means nothing usable was heard. */
    const val MIN_LETTERS = 2

    /** Mean no_speech_prob above this is silence. */
    const val MAX_NO_SPEECH_PROB = 0.6f

    /** Mean avg_logprob below this is guesswork. Absent is -1 and never rejects. */
    const val MIN_AVG_LOGPROB = -1.0f

    data class Decision(val accepted: Boolean, val reason: String)

    fun evaluate(
        text: String?,
        noSpeechProb: Float,
        avgLogprob: Float,
        language: String? = null,
    ): Decision {
        val trimmed = text?.trim().orEmpty()
        val letters = trimmed.count { it.isLetter() }
        if (letters < MIN_LETTERS) return Decision(false, "blank")
        // Jarvis speaks Arabic and English only: another detected language, or text
        // with no Arabic or Latin letters, is Whisper guessing at noise or echo.
        if (!supportedLanguage(language)) return Decision(false, "language")
        if (trimmed.none { it in '\u0600'..'\u06FF' || it in 'a'..'z' || it in 'A'..'Z' }) {
            return Decision(false, "language")
        }
        if (noSpeechProb >= 0f && noSpeechProb > MAX_NO_SPEECH_PROB) {
            return Decision(false, "no-speech")
        }
        if (avgLogprob < MIN_AVG_LOGPROB) {
            return Decision(false, "logprob")
        }
        if (hallucinates(trimmed)) return Decision(false, "hallucination")
        return Decision(true, "ok")
    }

    /** Unknown (null/blank) passes; Whisper names ("Arabic") and codes ("ar") both match. */
    fun supportedLanguage(language: String?): Boolean {
        val lang = language?.trim()?.lowercase().orEmpty()
        if (lang.isEmpty()) return true
        return lang == "ar" || lang == "en" || lang.startsWith("arabic") || lang.startsWith("english") ||
            lang.startsWith("ar-") || lang.startsWith("en-")
    }

    fun hallucinates(text: String): Boolean {
        val flat = normalize(text)
        if (flat.isEmpty()) return false
        if (flat in EXACT_MATCHES) return true
        return CONTAINS_MATCHES.any(flat::contains)
    }

    /** Credits and subtitler lines. A real utterance never carries these. */
    private val CONTAINS_MATCHES = listOf(
        "thanksforwatching",
        "thankyouforwatching",
        "ترجمهنانسيقنقر",
        "اشتركفيالقناه",
        "subtitlesby",
        "amaraorg",
        "pleaseconsiderdonating",
    )

    /** Whole-utterance silence answers like "Thank you." */
    private val EXACT_MATCHES = listOf(
        "thankyou",
        "thankyousomuch",
        "شكرا",
        "شكرالكم",
    )

    private fun normalize(text: String): String {
        val folded = text
            .lowercase()
            .replace('ة', 'ه')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
        return buildString {
            folded.forEach { char ->
                if (char.isLetterOrDigit()) append(char)
            }
        }
    }

    private val HALLUCINATIONS = listOf(
        "thankyou",
        "thanksforwatching",
        "thankyouforwatching",
        "thankyousomuch",
        "شكرا",
        "شكرالكم",
        "ترجمهنانسيقنقر",
        "اشتركفيالقناه",
        "subtitlesby",
        "amaraorg",
        "pleaseconsiderdonating",
    )
}
