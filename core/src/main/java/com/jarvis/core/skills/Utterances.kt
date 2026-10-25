package com.jarvis.core.skills

object Utterances {
    /**
     * Lowercase, strip Arabic diacritics, fold hamza and teh marbuta, drop punctuation.
     * Skill patterns must use this folded alphabet: أإآ→ا, ة→ه, ؤ→و, ئ→ي.
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.trim()) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                'ء' -> Unit
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '\u064B'..'\u065F', '\u0670', '\u0640' -> Unit
                '\'' , '’', 'ʻ', '`' -> Unit
                '-' -> sb.append(' ')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
            .replace(Regex("[\\p{Punct}؟،؛«»]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
