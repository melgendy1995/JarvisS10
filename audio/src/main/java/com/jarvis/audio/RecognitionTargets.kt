package com.jarvis.audio

/**
 * Which [android.speech.RecognitionService] to bind. The secure setting wins when that
 * component is actually visible to us. Otherwise prefer Speech Services by Google, then
 * the Google app. An empty list means package visibility hid every service, and the
 * caller must record and use the Whisper path instead.
 */
internal data class RecognitionTarget(
    val packageName: String,
    val className: String,
)

internal const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
internal const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

internal fun pickRecognitionTarget(
    available: List<RecognitionTarget>,
    selected: RecognitionTarget?,
): RecognitionTarget? {
    if (available.isEmpty()) return null
    if (selected != null && selected in available) return selected
    available.firstOrNull { it.packageName == GOOGLE_TTS_PACKAGE }?.let { return it }
    available.firstOrNull { it.packageName == GOOGLE_APP_PACKAGE }?.let { return it }
    return available.first()
}
