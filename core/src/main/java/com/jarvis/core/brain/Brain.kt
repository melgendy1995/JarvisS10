package com.jarvis.core.brain

import java.time.Duration
import kotlinx.coroutines.flow.Flow

/** Which brain produced an answer. Colors live on the orb; this is the name the chain carries. */
enum class BrainAccent(val label: String) {
    PHONE("Phone"),
    GEMINI("Gemini"),
    GROQ("Groq"),
    OFFLINE("Offline"),
}

enum class Speaker {
    USER,
    ASSISTANT,
}

/** One remembered line. The local list is what a later brain continues from. */
data class ConversationTurn(
    val speaker: Speaker,
    val text: String,
)

/** A fact the phone already stored. Providers only see it inside a fitted prompt. */
data class MemoryFact(
    val key: String,
    val value: String,
    val pinned: Boolean = false,
)

data class BrainRequest(
    val utterance: String,
    /** Prior lines only. The current utterance is [utterance], not a duplicate last turn. */
    val turns: List<ConversationTurn> = emptyList(),
    /** "About me" persona. The editor caps it; [PromptFitter] caps it again. */
    val persona: String = "",
    /** Pinned facts are kept ahead of the others when the prompt is trimmed. */
    val facts: List<MemoryFact> = emptyList(),
    /** Rolling summary of turns that fell out of the recent window. */
    val summary: String = "",
)

/**
 * Failures the chain can fall through.
 * [RateLimited.retryAfter] is how long to wait before trying this provider again.
 * [RateLimited.isDaily] is true when the cooldown runs until the next Pacific midnight.
 */
sealed class BrainError {
    data class RateLimited(
        val retryAfter: Duration,
        val isDaily: Boolean,
    ) : BrainError()

    data object Unavailable : BrainError()

    data object NotConfigured : BrainError()
}

/** Events from a Live session after the first turn has already been returned. */
sealed interface LiveEvent {
    data class Audio(val pcm24k: ByteArray) : LiveEvent
    data class Transcript(val text: String) : LiveEvent
    data class NeedsPermission(val permission: String) : LiveEvent
    data object TurnComplete : LiveEvent
    data object Closed : LiveEvent
}

/**
 * An open Gemini Live socket. The caller streams 16 kHz PCM and plays 24 kHz PCM.
 * Close it when the follow-up window ends.
 */
interface LiveFollowUp {
    val followUpWindow: Duration
    val events: Flow<LiveEvent>
    suspend fun sendMicPcm16k(frame: ByteArray)
    suspend fun close()
}

sealed class BrainResult {
    data class Answer(
        val brainId: String,
        val accent: BrainAccent,
        val text: String,
        val success: Boolean = true,
        val needsPermission: String? = null,
        /** 16-bit little-endian mono PCM at 24 kHz, when the brain already spoke. */
        val audioPcm24k: ByteArray? = null,
        val followUp: LiveFollowUp? = null,
        /** Short shelf label, such as the weather reading. Speech still uses [text]. */
        val shelf: String? = null,
        /** Google Search suggestions and sources. Null when the answer was not grounded. */
        val search: SearchDisplay? = null,
    ) : BrainResult()

    data class Failure(
        val brainId: String,
        val error: BrainError,
        val text: String = "",
    ) : BrainResult()
}

interface Brain {
    val id: String
    val accent: BrainAccent
    suspend fun reply(request: BrainRequest): BrainResult

    /**
     * One-shot text used only to fold on-device memory.
     * Live audio brains leave this as [BrainError.NotConfigured].
     */
    suspend fun summarize(prompt: String): BrainResult {
        return BrainResult.Failure(id, BrainError.NotConfigured)
    }
}
