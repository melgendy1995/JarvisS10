package com.jarvis.data

object MemoryLimits {
    const val PERSONA_CHARS = 2_000
    const val SUMMARY_CHARS = 800
    const val KEEP_TURNS = 20

    /** Ask a configured cloud brain to rewrite the summary after this many pruned turns. */
    const val SUMMARIZE_EVERY = 8
    const val TOP_FACTS = 8
    const val KEY_CHARS = 80
    const val VALUE_CHARS = 500
}
