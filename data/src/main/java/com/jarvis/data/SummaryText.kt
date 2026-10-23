package com.jarvis.data

object SummaryText {
    fun line(role: String, text: String): String = "${role.trim()}: ${text.trim()}"

    /** On-device fold used when a cloud brain is not asked, or is not available. */
    fun truncate(previous: String, lines: List<String>, maxChars: Int = MemoryLimits.SUMMARY_CHARS): String {
        val addition = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val combined = listOf(previous.trim(), addition).filter { it.isNotEmpty() }.joinToString(" ")
        if (maxChars <= 0) return ""
        if (combined.length <= maxChars) return combined
        return combined.takeLast(maxChars).trim()
    }

    fun cloudPrompt(summary: String): String {
        return "Rewrite this conversation memory in under 600 characters. " +
            "Keep names, decisions, and open questions. Reply with the summary only.\n\n" +
            summary.trim()
    }
}
