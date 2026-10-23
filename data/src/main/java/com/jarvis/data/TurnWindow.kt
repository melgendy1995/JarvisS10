package com.jarvis.data

/** Keeps the newest turns and returns the older ones that should fold into the summary. */
object TurnWindow {
    data class Decision<T>(
        val keep: List<T>,
        val dropped: List<T>,
    )

    fun <T> prune(turns: List<T>, keep: Int = MemoryLimits.KEEP_TURNS): Decision<T> {
        val limit = keep.coerceAtLeast(0)
        if (turns.size <= limit) return Decision(turns, emptyList())
        return Decision(
            keep = turns.takeLast(limit),
            dropped = turns.dropLast(limit),
        )
    }
}
