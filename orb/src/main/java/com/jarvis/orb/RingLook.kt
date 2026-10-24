package com.jarvis.orb

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Motion and color for one orb state. Instances in [table] are reused so a frame does not allocate.
 * Colors are straight sRGB 0..1. Overlapping additive sprites push the hot cores toward white.
 */
internal class RingLook(
    val coreR: Float,
    val coreG: Float,
    val coreB: Float,
    val hotR: Float,
    val hotG: Float,
    val hotB: Float,
    val spin: Float,
    val expand: Float,
    val breatheHz: Float,
    val breatheAmt: Float,
    val pulse: Float,
    val vortex: Float,
    val width: Float,
    val gain: Float,
    val flash: Float,
)

private val table = arrayOf(
    // Idle: slow breath, deep blue / cyan, rings parked.
    RingLook(
        coreR = 0.02f, coreG = 0.10f, coreB = 0.42f,
        hotR = 0.05f, hotG = 0.48f, hotB = 0.95f,
        spin = 0.12f, expand = 0f, breatheHz = 0.16f, breatheAmt = 0.055f,
        pulse = 0.02f, vortex = 0.06f, width = 0.13f, gain = 0.55f, flash = 0f,
    ),
    // Listening: bright cyan, rings travel outward, level speeds them up.
    RingLook(
        coreR = 0.15f, coreG = 0.75f, coreB = 1f,
        hotR = 0.35f, hotG = 1f, hotB = 1f,
        spin = 0.45f, expand = 1f, breatheHz = 0.35f, breatheAmt = 0.02f,
        pulse = 0.42f, vortex = 0.12f, width = 0.17f, gain = 1f, flash = 0f,
    ),
    // Thinking: fast magenta / purple vortex.
    RingLook(
        coreR = 0.42f, coreG = 0.02f, coreB = 0.72f,
        hotR = 1f, hotG = 0.12f, hotB = 1f,
        spin = 3.6f, expand = 0.18f, breatheHz = 0.7f, breatheAmt = 0.03f,
        pulse = 0.06f, vortex = 1.55f, width = 0.11f, gain = 0.95f, flash = 0f,
    ),
    // Speaking: gold / orange, size and brightness follow the envelope.
    RingLook(
        coreR = 0.95f, coreG = 0.42f, coreB = 0.02f,
        hotR = 1f, hotG = 0.68f, hotB = 0.08f,
        spin = 0.38f, expand = 0.06f, breatheHz = 1.1f, breatheAmt = 0.025f,
        pulse = 0.72f, vortex = 0.16f, width = 0.19f, gain = 0.92f, flash = 0f,
    ),
    // Error: red flash.
    RingLook(
        coreR = 0.72f, coreG = 0.02f, coreB = 0.03f,
        hotR = 1f, hotG = 0.06f, hotB = 0.05f,
        spin = 0.08f, expand = 0.04f, breatheHz = 2.2f, breatheAmt = 0.02f,
        pulse = 0.08f, vortex = 0.04f, width = 0.15f, gain = 1f, flash = 1f,
    ),
)

internal fun OrbState.look(): RingLook = table[ordinal]

/** Debug-broadcast motion so a forced state is obviously alive with no microphone. */
internal fun previewLevel(state: OrbState, seconds: Float): Float {
    val wave = when (state) {
        OrbState.LISTENING -> 0.5f + 0.5f * sin(seconds * 2.6f)
        OrbState.SPEAKING -> {
            val raw = sin(seconds * PI.toFloat() * 4.7f) * 0.6f +
                sin(seconds * PI.toFloat() * 2.9f + 0.7f) * 0.4f
            raw.coerceAtLeast(0f).pow(1.6f)
        }
        OrbState.ERROR -> if (sin(seconds * 12f) >= 0f) 1f else 0.2f
        OrbState.THINKING -> 0.35f
        OrbState.IDLE -> 0.15f
    }
    return wave.coerceIn(0f, 1f)
}

/** Mutable copy. [approach] is the smooth state transition, with no allocation. */
internal class RingParams {
    var coreR = 0f
    var coreG = 0f
    var coreB = 0f
    var hotR = 0f
    var hotG = 0f
    var hotB = 0f
    var spin = 0f
    var expand = 0f
    var breatheHz = 0f
    var breatheAmt = 0f
    var pulse = 0f
    var vortex = 0f
    var width = 0f
    var gain = 0f
    var flash = 0f

    fun copyFrom(look: RingLook) {
        coreR = look.coreR
        coreG = look.coreG
        coreB = look.coreB
        hotR = look.hotR
        hotG = look.hotG
        hotB = look.hotB
        spin = look.spin
        expand = look.expand
        breatheHz = look.breatheHz
        breatheAmt = look.breatheAmt
        pulse = look.pulse
        vortex = look.vortex
        width = look.width
        gain = look.gain
        flash = look.flash
    }

    fun approach(look: RingLook, k: Float) {
        coreR += (look.coreR - coreR) * k
        coreG += (look.coreG - coreG) * k
        coreB += (look.coreB - coreB) * k
        hotR += (look.hotR - hotR) * k
        hotG += (look.hotG - hotG) * k
        hotB += (look.hotB - hotB) * k
        spin += (look.spin - spin) * k
        expand += (look.expand - expand) * k
        breatheHz += (look.breatheHz - breatheHz) * k
        breatheAmt += (look.breatheAmt - breatheAmt) * k
        pulse += (look.pulse - pulse) * k
        vortex += (look.vortex - vortex) * k
        width += (look.width - width) * k
        gain += (look.gain - gain) * k
        flash += (look.flash - flash) * k
    }

    companion object {
        fun from(look: RingLook): RingParams = RingParams().apply { copyFrom(look) }
    }
}
