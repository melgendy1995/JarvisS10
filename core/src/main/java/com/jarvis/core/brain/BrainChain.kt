package com.jarvis.core.brain

import com.jarvis.core.CommandRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * On-device router, then each [brains] entry in order:
 * Gemini Live, Gemini text, Groq, then the offline reply.
 * The same [BrainRequest] is passed along, so a later brain still has [BrainRequest.turns]
 * when Gemini fails or is capped.
 * [BrainError.RateLimited], [BrainError.Unavailable], and [BrainError.NotConfigured] fall through.
 */
class BrainChain(
    private val router: CommandRouter,
    private val brains: List<Brain> = emptyList(),
) {
    suspend fun reply(request: BrainRequest): BrainResult {
        val routed = router.route(request.utterance)
        if (!CommandRouter.isMiss(routed)) {
            return BrainResult.Answer(
                brainId = PHONE_ID,
                accent = BrainAccent.PHONE,
                text = routed.text,
                success = routed.success,
                needsPermission = routed.needsPermission,
            )
        }
        var last: BrainError = BrainError.NotConfigured
        for (brain in brains) {
            val result = try {
                brain.reply(request)
            } catch (cancelled: CancellationException) {
                // A live socket actor completes by cancelling its channel. That throws
                // CancellationException while this caller is still active, and it must not
                // skip the rest of the chain. A real parent cancel still propagates.
                if (!currentCoroutineContext().isActive) throw cancelled
                BrainResult.Failure(brain.id, BrainError.Unavailable)
            } catch (_: Throwable) {
                BrainResult.Failure(brain.id, BrainError.Unavailable)
            }
            when (result) {
                is BrainResult.Answer -> return result
                is BrainResult.Failure -> last = result.error
            }
        }
        return exhausted(last)
    }

    private fun exhausted(last: BrainError): BrainResult.Failure {
        val text = when (last) {
            is BrainError.RateLimited ->
                "The cloud brains are cooling down. I can still tell the time, set a timer, or toggle the flashlight."
            BrainError.NotConfigured ->
                "A cloud brain isn't set up yet. I can still tell the time, set a timer, or toggle the flashlight."
            BrainError.Unavailable ->
                "I can't reach a cloud brain right now. I can still tell the time, set a timer, or toggle the flashlight."
        }
        return BrainResult.Failure(brainId = OFFLINE_ID, error = last, text = text)
    }

    companion object {
        const val PHONE_ID = "phone"
        const val OFFLINE_ID = "offline"
    }
}
