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

/**
 * Everything one listen needs: the service to bind and the language extras.
 */
internal data class RecognizerChoice(
    val target: RecognitionTarget?,
    val primaryTag: String,
    val extraTags: List<String>,
    /** EXTRA_LANGUAGE_PREFERENCE, only set on the online ar attempt. */
    val preferenceTag: String? = null,
    val preferOffline: Boolean,
    val setting: String,
) {
    val targetName: String = target?.packageName ?: "default"
}

/**
 * Pure per-language recognizer choice, offline-first for every setting.
 * "ar" and "auto" never bind the online Google app recognizer: the primary
 * path for them is Jarvis's own capture plus Whisper, and the recognizer is
 * only ever a fallback. "ar" asks ar-SA with en-US as the second language,
 * "auto" keeps the device tag and offers ar-SA. [forceOffline] is kept for
 * compat and changes nothing now that the online preference is gone.
 */
internal fun chooseRecognizer(
    setting: String,
    deviceTag: String,
    available: List<RecognitionTarget>,
    selected: RecognitionTarget?,
    forceOffline: Boolean = false,
): RecognizerChoice {
    if (available.isEmpty()) {
        return RecognizerChoice(null, deviceTag, emptyList(), preferOffline = true, setting = setting)
    }
    return RecognizerChoice(
        target = pickRecognitionTarget(available, selected),
        primaryTag = VoiceLanguageTags.resolve(setting, deviceTag),
        extraTags = when (setting) {
            "ar" -> listOf("en-US")
            "en" -> emptyList()
            else -> listOf("ar-SA")
        },
        preferOffline = true,
        setting = setting,
    )
}
