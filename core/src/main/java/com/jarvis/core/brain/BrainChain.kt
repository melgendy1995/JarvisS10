package com.jarvis.core.brain

import com.jarvis.core.CommandRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope

/**
 * On-device router, then each [brains] entry in order:
 * Gemini Live, Gemini text, Groq, then the offline reply.
 * The same [BrainRequest] is passed along, so a later brain still has [BrainRequest.turns]
 * when Gemini fails or is capped.
 * [BrainError.RateLimited], [BrainError.Unavailable], and [BrainError.NotConfigured] fall through.
 *
 * Each brain runs in its own supervisor child. A socket that cancels itself becomes
 * [BrainError.Unavailable] and the next brain still runs. Cancelling this caller
 * still cancels the chain.
 */
class BrainChain(
    private val router: CommandRouter,
    private val brains: List<Brain> = emptyList(),
    private val log: (String) -> Unit = {},
) {
    suspend fun reply(request: BrainRequest): BrainResult {
        val started = System.nanoTime()
        val routed = router.route(request.utterance)
        if (!CommandRouter.isMiss(routed)) {
            logStep(PHONE_ID, "answer", started)
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
            val step = System.nanoTime()
            val result = try {
                supervisorScope {
                    val child = async { brain.reply(request) }
                    try {
                        child.await()
                    } catch (cancelled: CancellationException) {
                        if (!isActive) throw cancelled
                        BrainResult.Failure(brain.id, BrainError.Unavailable)
                    }
                }
            } catch (cancelled: CancellationException) {
                logStep(brain.id, "cancelled", step)
                if (!currentCoroutineContext().isActive) throw cancelled
                BrainResult.Failure(brain.id, BrainError.Unavailable)
            } catch (_: Throwable) {
                BrainResult.Failure(brain.id, BrainError.Unavailable)
            }
            val outcome = when (result) {
                is BrainResult.Answer -> "answer"
                is BrainResult.Failure -> "failure:${result.error.javaClass.simpleName}"
            }
            logStep(brain.id, outcome, step)
            when (result) {
                is BrainResult.Answer -> return result
                is BrainResult.Failure -> last = result.error
            }
        }
        val offline = exhausted(last)
        log("brain=$OFFLINE_ID outcome=failure:${last.javaClass.simpleName} elapsedMs=0")
        return offline
    }

    private fun logStep(brainId: String, outcome: String, startedNs: Long) {
        val elapsed = (System.nanoTime() - startedNs) / 1_000_000L
        log("brain=$brainId outcome=$outcome elapsedMs=$elapsed")
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
