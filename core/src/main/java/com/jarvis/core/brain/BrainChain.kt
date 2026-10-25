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
                shelf = routed.shelf,
            )
        }
        var last: BrainError = BrainError.NotConfigured
        val answer = runBrains(request, skip = null) { last = it }
        // One retry, never a loop: a memory-only answer for a live question gets
        // a second pass with search forced, skipping the brain that lied stale.
        // Kept only when it answers.
        if (
            answer is BrainResult.Answer &&
            answer.search == null &&
            StaleAnswer.needsLiveRetry(answer.text)
        ) {
            log("stale-answer retry forced=live-search")
            val retried = runBrains(request.copy(forceLiveSearch = true), skip = answer.brainId) { last = it }
            if (retried is BrainResult.Answer) return guard(request, logAnswer(retried, started))
        }
        if (answer is BrainResult.Answer) return guard(request, logAnswer(answer, started))
        return logOffline(last, started)
    }

    /**
     * Cloud brains like to confirm actions the phone never made. When a reply
     * claims one, reroute what the user said (or the closest command rebuilt
     * from the claim) so a real skill answers, else speak an honest line.
     */
    private suspend fun guard(request: BrainRequest, answer: BrainResult.Answer): BrainResult.Answer {
        if (answer.toolRan) return answer
        val claim = ActionClaimGuard.detect(answer.text) ?: return answer
        val attempts = sequenceOf(request.utterance).plus(
            ActionClaimGuard.canonical(claim)?.let { sequenceOf(it) } ?: emptySequence(),
        ).filter { it.isNotBlank() }
        for (candidate in attempts) {
            val routed = router.route(candidate)
            if (CommandRouter.isMiss(routed)) continue
            log("guard claim=${claim.id} action=routed")
            return BrainResult.Answer(
                brainId = answer.brainId,
                accent = BrainAccent.PHONE,
                text = routed.text,
                success = routed.success,
                needsPermission = routed.needsPermission,
                shelf = routed.shelf,
            )
        }
        log("guard claim=${claim.id} action=honest")
        return answer.copy(
            text = ActionClaimGuard.honest(claim),
            audioPcm24k = null,
            search = null,
            shelf = null,
        )
    }

    private fun logAnswer(answer: BrainResult.Answer, started: Long): BrainResult.Answer {
        logStep(answer.brainId, "answer", started)
        return answer
    }

    private fun logOffline(last: BrainError, started: Long): BrainResult.Failure {
        val offline = exhausted(last)
        log("brain=$OFFLINE_ID outcome=failure:${last.javaClass.simpleName} elapsedMs=0")
        return offline
    }

    private suspend fun runBrains(
        request: BrainRequest,
        skip: String?,
        onError: (BrainError) -> Unit,
    ): BrainResult {
        var last: BrainError = BrainError.NotConfigured
        for (brain in brains) {
            if (brain.id == skip) continue
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
        onError(last)
        return exhausted(last)
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
