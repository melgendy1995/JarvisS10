package com.jarvis.orb

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR,
}

/** Brain accent. Values are the sRGB hex colors from the UI reference, as linear 0..1 channels. */
enum class BrainAccent(
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    PHONE(0x3f / 255f, 0xd8 / 255f, 0xff / 255f),
    GEMINI(0x8c / 255f, 0x73 / 255f, 0xff / 255f),
    GROQ(0xff / 255f, 0x9e / 255f, 0x40 / 255f),
    OFFLINE(0x73 / 255f, 0x80 / 255f, 0x8c / 255f),
}

internal class OrbTarget(
    var brightness: Float,
    var radius: Float,
    var swirl: Float,
    var speed: Float,
    var noise: Float,
    var scatter: Float,
    var agitation: Float,
    var colorMix: Float,
    var pointSize: Float,
    var flicker: Float,
    var tintR: Float,
    var tintG: Float,
    var tintB: Float,
    var flat: Float,
)

/**
 * Ports the per-state targets in docs/jarvis-ui.html.
 * `flat` is the prototype's `fl` (squash the cloud). `flicker` is extra motion on top of that.
 */
internal fun OrbState.target(): OrbTarget = when (this) {
    OrbState.IDLE -> OrbTarget(
        brightness = 0.38f, radius = 1.0f, swirl = 0.15f, speed = 0.25f, noise = 0.10f,
        scatter = 0f, agitation = 0f, colorMix = 0.25f, pointSize = 1.0f, flicker = 0.04f,
        tintR = 1f, tintG = 1f, tintB = 1f, flat = 0f,
    )
    OrbState.LISTENING -> OrbTarget(
        brightness = 0.95f, radius = 1.05f, swirl = 0.25f, speed = 0.6f, noise = 0.16f,
        scatter = 0f, agitation = 1.0f, colorMix = 0.35f, pointSize = 1.15f, flicker = 0.08f,
        tintR = 0.9f, tintG = 1.05f, tintB = 1.1f, flat = 0f,
    )
    OrbState.THINKING -> OrbTarget(
        brightness = 1.0f, radius = 0.92f, swirl = 1.6f, speed = 1.6f, noise = 0.32f,
        scatter = 0f, agitation = 0.2f, colorMix = 0.9f, pointSize = 1.0f, flicker = 0.45f,
        tintR = 1.5f, tintG = 0.85f, tintB = 1.15f, flat = 0.6f,
    )
    OrbState.SPEAKING -> OrbTarget(
        brightness = 1.05f, radius = 1.0f, swirl = 0.35f, speed = 0.8f, noise = 0.14f,
        scatter = 0f, agitation = 1.3f, colorMix = 0.55f, pointSize = 1.2f, flicker = 0.12f,
        tintR = 1.1f, tintG = 1.05f, tintB = 1.0f, flat = 0.15f,
    )
    OrbState.ERROR -> OrbTarget(
        brightness = 0.30f, radius = 1.0f, swirl = 0.05f, speed = 0.15f, noise = 0.05f,
        scatter = 1f, agitation = 0f, colorMix = 0f, pointSize = 0.9f, flicker = 0.2f,
        tintR = 0.7f, tintG = 0.7f, tintB = 0.75f, flat = 0f,
    )
}
