package com.jarvis.audio.wake

import java.util.Locale

/**
 * Phrase checks for barge-in. Plain "jarvis" is not a wake model;
 * "jarvis stop" is spotted by the recognizer while Jarvis is speaking.
 */
object WakePhrases {
    enum class Barge { NONE, WAKE, STOP }

    private val stops = setOf(
        "jarvis stop",
        "hey jarvis stop",
        "stop jarvis",
        "jarvis be quiet",
        "hey jarvis be quiet",
    )

    fun normalize(text: String): String {
        return text.lowercase(Locale.US)
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isStop(text: String): Boolean {
        val n = normalize(text)
        return n in stops || n.startsWith("jarvis stop ") || n.startsWith("hey jarvis stop ")
    }

    fun barge(text: String, spoken: String): Barge {
        val n = normalize(text)
        if (n.isEmpty() || isLongEcho(n, spoken)) return Barge.NONE
        if (isStop(n)) return Barge.STOP
        if (n == "hey jarvis" || n.startsWith("hey jarvis ")) return Barge.WAKE
        return Barge.NONE
    }

    /** Words after the wake phrase, when the recognizer already heard the command. */
    fun remainder(text: String): String? {
        val n = normalize(text)
        val rest = when {
            n.startsWith("hey jarvis ") -> n.removePrefix("hey jarvis ").trim()
            else -> ""
        }
        if (rest.isEmpty() || isStop(n) || rest == "stop") return null
        return rest
    }

    private fun isLongEcho(heard: String, spoken: String): Boolean {
        if (heard.length < 24) return false
        val said = normalize(spoken)
        return said.isNotEmpty() && said.contains(heard)
    }
}
