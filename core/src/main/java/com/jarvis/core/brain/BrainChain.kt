package com.jarvis.core.brain

import com.jarvis.core.CommandRouter
import kotlinx.coroutines.CancellationException

/**
 * On-device router first, then each [brains] entry in order.
 * Gemini Live, Gemini text, and later Groq or the local model are just more brains.
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
                throw cancelled
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
                "Gemini is cooling down. I can still tell the time, set a timer, or toggle the flashlight."
            BrainError.NotConfigured ->
                "Gemini isn't set up yet. I can still tell the time, set a timer, or toggle the flashlight."
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
