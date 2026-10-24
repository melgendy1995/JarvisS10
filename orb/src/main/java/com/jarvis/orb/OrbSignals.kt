package com.jarvis.orb

/**
 * Mic RMS and TTS level, written off the composition clock.
 * The orb reads this on the draw thread so a 20 Hz envelope does not rebuild the shelf.
 */
object OrbSignals {
    @Volatile
    var level: Float = 0f
}
