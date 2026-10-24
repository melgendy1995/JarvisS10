package com.jarvis.core.skills

/** Maps a spoken amount onto [min, max] for STREAM_MUSIC. */
object VolumeLevels {
    fun resolve(min: Int, max: Int, amount: Int, unit: String): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val span = (hi - lo).coerceAtLeast(1)
        val raw = when (unit) {
            "percent" -> lo + (span * amount.coerceIn(0, 100) + 50) / 100
            "level" -> amount
            else -> if (amount in lo..hi) {
                amount
            } else {
                lo + (span * amount.coerceIn(0, 100) + 50) / 100
            }
        }
        return raw.coerceIn(lo, hi)
    }
}
