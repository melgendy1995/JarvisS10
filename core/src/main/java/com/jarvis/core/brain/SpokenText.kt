package com.jarvis.core.brain

/** Text that can be spoken. URLs, markdown, and citation markers are removed. */
object SpokenText {
    fun forSpeech(raw: String): String {
        var text = raw.replace("\r\n", "\n")
        text = MARKDOWN_LINK.replace(text) { match -> match.groupValues[1] }
        text = BARE_URL.replace(text, "")
        text = WWW.replace(text, "")
        text = BRACKET_CITE.replace(text, "")
        text = FULLWIDTH_CITE.replace(text, "")
        text = text.replace("**", "").replace("__", "").replace("`", "")
        text = HEADING.replace(text, "")
        text = BULLET.replace(text, "")
        text = text.replace('\n', ' ')
        text = text.replace(Regex("""\s+"""), " ")
        text = text.replace(Regex("""\s+([,.;!?])"""), "$1")
        text = text.replace(Regex("""\(\s*\)"""), "")
        return text.trim()
    }

    private val MARKDOWN_LINK = Regex("""\[([^]]+)]\((?:https?://|www\.)[^)]+\)""")
    private val BARE_URL = Regex("""https?://\S+""")
    private val WWW = Regex("""\bwww\.\S+""")
    private val BRACKET_CITE = Regex("""\[\d{1,3}(?:\s*,\s*\d{1,3})*]""")
    private val FULLWIDTH_CITE = Regex("""【[^】]{0,80}】""")
    private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
    private val BULLET = Regex("""(?m)^\s*[-*•]\s+""")
}
