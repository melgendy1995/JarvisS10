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
 * The offline Speech Services by Google pack on this phone is English only, so
 * auto and ar ask for the online Google app recognizer instead.
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
 * Pure per-language recognizer choice. "en" keeps the offline-first behaviour;
 * "ar" and "auto" prefer the online Google app recognizer (no PREFER_OFFLINE) —
 * "ar" pins ar-SA with en-US as the second language, "auto" keeps the device
 * tag and offers ar-SA as the second language. When the Google app recognizer
 * is not visible or [forceOffline] is set, the existing fallback choice is used
 * with the offline extras. Returns target=null when no service is visible.
 */
internal fun chooseRecognizer(
    setting: String,
    deviceTag: String,
    available: List<RecognitionTarget>,
    selected: RecognitionTarget?,
    forceOffline: Boolean = false,
): RecognizerChoice {
    val fallbackTarget = pickRecognitionTarget(available, selected)
    if (available.isEmpty()) {
        return RecognizerChoice(null, deviceTag, emptyList(), preferOffline = true, setting = setting)
    }
    if (!forceOffline && setting != "en") {
        available.firstOrNull { it.packageName == GOOGLE_APP_PACKAGE }?.let { googleApp ->
            return if (setting == "ar") {
                RecognizerChoice(
                    target = googleApp,
                    primaryTag = "ar-SA",
                    extraTags = listOf("en-US"),
                    preferenceTag = "ar-SA",
                    preferOffline = false,
                    setting = setting,
                )
            } else {
                RecognizerChoice(
                    target = googleApp,
                    primaryTag = deviceTag,
                    extraTags = listOf("ar-SA"),
                    preferOffline = false,
                    setting = setting,
                )
            }
        }
    }
    return RecognizerChoice(
        target = fallbackTarget,
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
