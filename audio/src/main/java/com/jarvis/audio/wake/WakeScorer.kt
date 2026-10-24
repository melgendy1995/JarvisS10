package com.jarvis.audio.wake

import kotlin.math.ceil

/**
 * The openWakeWord predict() gate: the first [warmup] frames are forced to 0,
 * then a detection suppresses later frames inside the debounce window.
 */
class WakeScorer(
    private val warmup: Int = WakeConfig.WARMUP_FRAMES,
) {
    private val history = ArrayDeque<Float>()
    private var seen = 0

    fun reset() {
        history.clear()
        seen = 0
    }

    fun accept(
        raw: Float,
        threshold: Float,
        frameSeconds: Float = WakeConfig.FRAME_SECONDS,
        debounceSeconds: Float = WakeConfig.DEBOUNCE_SECONDS,
    ): Float {
        seen++
        var score = if (seen <= warmup) 0f else raw
        if (debounceSeconds > 0f && score >= threshold) {
            val frames = ceil(debounceSeconds / frameSeconds.toDouble()).toInt().coerceAtLeast(1)
            if (history.takeLast(frames).any { it >= threshold }) score = 0f
        }
        history.addLast(score)
        while (history.size > 30) history.removeFirst()
        return score
    }
}
