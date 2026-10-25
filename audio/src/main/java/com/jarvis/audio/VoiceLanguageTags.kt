package com.jarvis.audio

/**
 * Pure language-tag choice for the voice recognizer. AUTO inherits the phone
 * locale; EN and AR pin to stable tags, whatever the phone locale is.
 */
object VoiceLanguageTags {
    fun resolve(setting: String, deviceTag: String): String = when (setting) {
        "en" -> "en-US"
        "ar" -> "ar-SA"
        else -> deviceTag
    }
}
