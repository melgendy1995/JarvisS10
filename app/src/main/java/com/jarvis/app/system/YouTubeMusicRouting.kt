package com.jarvis.app.system

/**
 * Pure routing for "play X on YouTube Music". The direct
 * MEDIA_PLAY_FROM_SEARCH path mostly opens the search page without playing,
 * so the plan falls back to tapping a search result, and then to regular
 * YouTube when that still fails.
 */
enum class MusicPlayOutcome {
    PLAYING_ON_YT_MUSIC,
    PLAYING_ON_YOUTUBE,
    FAILED,
}

enum class MusicPlayStep {
    DIRECT_PLAY,
    TAP_YTM,
    YOUTUBE_FALLBACK,
}

object YouTubeMusicRouting {
    fun steps(directStarted: Boolean, ytmTapStarted: Boolean, youtubeAvailable: Boolean): List<MusicPlayStep> {
        val steps = ArrayList<MusicPlayStep>()
        steps += MusicPlayStep.DIRECT_PLAY
        if (directStarted) return steps
        steps += MusicPlayStep.TAP_YTM
        if (!ytmTapStarted && youtubeAvailable) steps += MusicPlayStep.YOUTUBE_FALLBACK
        return steps
    }

    fun outcome(
        directStarted: Boolean,
        ytmTapStarted: Boolean,
        youtubeAvailable: Boolean,
        youtubeStarted: Boolean,
    ): MusicPlayOutcome = when {
        directStarted || ytmTapStarted -> MusicPlayOutcome.PLAYING_ON_YT_MUSIC
        youtubeAvailable && youtubeStarted -> MusicPlayOutcome.PLAYING_ON_YOUTUBE
        else -> MusicPlayOutcome.FAILED
    }

    fun message(outcome: MusicPlayOutcome, arabic: Boolean): String = when (outcome) {
        MusicPlayOutcome.PLAYING_ON_YT_MUSIC ->
            if (arabic) "شغال على يوتيوب ميوزك." else "Playing on YouTube Music."
        MusicPlayOutcome.PLAYING_ON_YOUTUBE ->
            if (arabic) "يوتيوب ميوزك ما اشتغل، فشغلته على يوتيوب."
            else "YouTube Music wouldn't start it, so I'm playing it on YouTube."
        MusicPlayOutcome.FAILED ->
            if (arabic) "فتحت يوتيوب ميوزك، بس ما قدرت أشغل."
            else "I opened YouTube Music but couldn't start playback."
    }
}
