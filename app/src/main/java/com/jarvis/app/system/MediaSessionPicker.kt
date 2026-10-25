package com.jarvis.app.system

/**
 * Picks which media session Jarvis should drive. Pure decision logic, unit-testable
 * off-device; [Session] mirrors android.media.session.PlaybackState codes so the caller
 * can feed raw session state integers in.
 */
object MediaSessionPicker {
    const val STATE_NONE = 0
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3

    const val JARVIS = "com.jarvis.app"

    data class Session(
        val packageName: String,
        val state: Int,
        val lastActiveMs: Long,
    )

    /**
     * A named package always wins. Otherwise prefer a playing session over the most
     * recently active one, and never steer Jarvis into its own session.
     */
    fun choose(sessions: List<Session>, targetPackage: String?): Session? {
        if (targetPackage != null) {
            return sessions.firstOrNull { it.packageName == targetPackage }
        }
        val others = sessions.filter { it.packageName != JARVIS }
        others.firstOrNull { it.state == STATE_PLAYING }?.let { return it }
        return others.maxByOrNull { it.lastActiveMs }
    }
}
