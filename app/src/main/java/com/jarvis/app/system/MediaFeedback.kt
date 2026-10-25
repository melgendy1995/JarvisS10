package com.jarvis.app.system

/**
 * Pure decisions for what a media transport command may honestly claim.
 * [decide] never invents state: it only says "Paused." when a session actually
 * reports paused (or we detected playback right before acting), and it keeps
 * quiet about actions when nothing was playing. [MediaOutcome.Default] means
 * the caller falls back to its existing strings.
 */
object MediaFeedback {
    sealed interface MediaOutcome {
        /** Speak this instead of the default line. success=false is a hard failure. */
        data class Message(val text: String, val success: Boolean = true) : MediaOutcome
        data object Default : MediaOutcome

        companion object {
            fun nothingPlaying(arabic: Boolean): String = if (arabic) "ما في شي يشتغل." else "Nothing is playing."
        }
    }

    /**
     * [which] pause/resume/next/previous; [picked] a media session was chosen;
     * [stateBefore]/[stateAfter] the chosen session's PlaybackState around the action;
     * [playingBefore] playback was actually detected right before the action.
     */
    fun decide(
        which: String,
        arabic: Boolean,
        picked: Boolean,
        stateBefore: Int,
        stateAfter: Int,
        playingBefore: Boolean,
    ): MediaOutcome {
        val nothing = MediaOutcome.Message(MediaOutcome.nothingPlaying(arabic))
        return when (which) {
            "pause" -> when {
                picked && stateAfter == MediaSessionPicker.STATE_PLAYING -> cannotPause(arabic)
                picked && stateAfter == MediaSessionPicker.STATE_PAUSED -> paused(arabic)
                !picked && !playingBefore && stateAfter != MediaSessionPicker.STATE_PAUSED -> nothing
                else -> MediaOutcome.Default
            }
            "resume" -> when {
                !picked && !playingBefore -> nothing
                picked && stateAfter == MediaSessionPicker.STATE_PLAYING -> playing(arabic)
                else -> MediaOutcome.Default
            }
            else -> {
                if (!picked && !playingBefore) nothing else MediaOutcome.Default
            }
        }
    }

    private fun paused(arabic: Boolean) =
        MediaOutcome.Message(if (arabic) "تم الإيقاف." else "Paused.")

    private fun playing(arabic: Boolean) =
        MediaOutcome.Message(if (arabic) "كملت." else "Playing.")

    private fun cannotPause(arabic: Boolean) = MediaOutcome.Message(
        if (arabic) "ما قدرت أوقفه." else "I couldn't pause it.",
        success = false,
    )
}
