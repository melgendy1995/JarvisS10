package com.jarvis.app.system

import android.content.Context

/** Voice input language: auto follows the phone locale with an Arabic Whisper re-check. */
object VoiceLanguageStore {
    const val AUTO = "auto"
    const val EN = "en"
    const val AR = "ar"
    val VALUES = listOf(AUTO, EN, AR)

    private const val PREFS = "jarvis_voice"
    private const val KEY = "voice_language"

    fun get(context: Context): String {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO)
        return if (saved in VALUES) saved!! else AUTO
    }

    fun set(context: Context, value: String) {
        if (value !in VALUES) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value)
            .apply()
    }
}
