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
