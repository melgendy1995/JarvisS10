package com.jarvis.audio

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/** Applies the English Jarvis voice and logs what the engine actually has. */
object JarvisTts {
    const val TAG = "JarvisTts"

    fun applyEnglish(tts: TextToSpeech): String {
        var listed = readVoices(tts)
        // Some engines leave getVoices() empty until a language has been set.
        if (listed.isEmpty()) {
            tts.language = Locale.UK
            listed = readVoices(tts)
        }
        val availability = tts.isLanguageAvailable(Locale.UK)
        val missingData = availability == TextToSpeech.LANG_MISSING_DATA
        val names = EnglishVoices.enGbNames(listed)
        val picked = EnglishVoices.pick(listed)
        val installed = picked?.let { choice -> tts.voices?.firstOrNull { it.name == choice.name } }
        tts.setPitch(EnglishVoices.PITCH)
        tts.setSpeechRate(EnglishVoices.RATE)
        val applied = when {
            installed != null && tts.setVoice(installed) == TextToSpeech.SUCCESS -> installed.name
            picked != null -> {
                val locale = if (picked.country.equals("GB", ignoreCase = true)) Locale.UK else Locale.US
                tts.language = locale
                "locale:${locale.toLanguageTag()}"
            }
            else -> {
                // No installed en-GB voice, and no offline en-US voice either.
                tts.language = Locale.US
                "locale:en-US"
            }
        }
        Log.i(
            TAG,
            "en-GB voices=$names missingData=$missingData availability=$availability " +
                "picked=$applied systemDefault=${tts.defaultEngine} voiceCount=${listed.size}",
        )
        return applied
    }

    internal fun snapshot(tts: TextToSpeech): List<ListedVoice> = readVoices(tts)

    internal fun hasOfflineBritish(tts: TextToSpeech): Boolean {
        val picked = EnglishVoices.pick(readVoices(tts)) ?: return false
        return EnglishVoices.isBritish(picked)
    }

    private fun readVoices(tts: TextToSpeech): List<ListedVoice> {
        return tts.voices.orEmpty().map { it.toListed() }
    }

    private fun Voice.toListed(): ListedVoice {
        return ListedVoice(
            name = name,
            language = locale?.language.orEmpty(),
            country = locale?.country.orEmpty(),
            networkRequired = isNetworkConnectionRequired,
            features = features.orEmpty(),
        )
    }
}
