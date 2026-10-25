package com.jarvis.core.brain

/**
 * Language of the user's line: any Arabic-script character means Arabic,
 * anything else is English. Used per request, never a global setting.
 */
object AnswerLanguage {
    fun isArabic(text: String): Boolean {
        for (ch in text) {
            if (ch in '\u0600'..'\u06FF' ||
                ch in '\u0750'..'\u077F' ||
                ch in '\u08A0'..'\u08FF' ||
                ch in '\uFB50'..'\uFDFF' ||
                ch in '\uFE70'..'\uFEFF'
            ) {
                return true
            }
        }
        return false
    }

    /** Explicit per-request line appended to the cloud system prompts. */
    fun instruction(text: String): String {
        return if (isArabic(text)) "Reply in Arabic." else "Reply in English."
    }
}
