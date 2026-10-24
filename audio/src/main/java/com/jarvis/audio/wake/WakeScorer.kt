package com.jarvis.audio.wake

/**
 * Passive wake gate: the first [warmup] frames are forced to 0, then a frame
 * fires when it is at or above [threshold], or when [softHits] of the last
 * [softWindow] frames are at or above [softThreshold]. A trigger starts a
 * refractory window that suppresses later hits.
 */
class WakeScorer(
    private val warmup: Int = WakeConfig.WARMUP_FRAMES,
) {
    private val recent = ArrayDeque<Float>()
    private var seen = 0
    private var time = 0f
    private var lastTrigger = -Float.MAX_VALUE

    fun reset() {
        recent.clear()
        seen = 0
        time = 0f
        lastTrigger = -Float.MAX_VALUE
    }

    fun accept(
        raw: Float,
        threshold: Float = WakeConfig.THRESHOLD,
        frameSeconds: Float = WakeConfig.FRAME_SECONDS,
        refractorySeconds: Float = WakeConfig.REFRACTORY_SECONDS,
        allowSoft: Boolean = true,
        softThreshold: Float = WakeConfig.SOFT_THRESHOLD,
        softWindow: Int = WakeConfig.SOFT_WINDOW,
        softHits: Int = WakeConfig.SOFT_HITS,
    ): Float {
        seen++
        if (seen <= warmup) {
            time += frameSeconds
            return 0f
        }
        recent.addLast(raw)
        while (recent.size > softWindow) recent.removeFirst()
        val hard = raw >= threshold
        val soft = allowSoft && recent.count { it >= softThreshold } >= softHits
        val open = time - lastTrigger >= refractorySeconds
        val triggered = (hard || soft) && open
        if (triggered) lastTrigger = time
        time += frameSeconds
        return if (triggered) raw else 0f
    }
}
