package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing decisions for "play X on YouTube Music" when direct play misbehaves. */
class YouTubeMusicRoutingTest {
    @Test
    fun directPlayFailingReachesTheTapStep() {
        val steps = YouTubeMusicRouting.steps(
            directStarted = false,
            ytmTapStarted = true,
            youtubeAvailable = false,
        )
        assertEquals(listOf(MusicPlayStep.DIRECT_PLAY, MusicPlayStep.TAP_YTM), steps)
        assertEquals(
            MusicPlayOutcome.PLAYING_ON_YT_MUSIC,
            YouTubeMusicRouting.outcome(
                directStarted = false,
                ytmTapStarted = true,
                youtubeAvailable = false,
                youtubeStarted = false,
            ),
        )
    }

    @Test
    fun directPlaySuccessSkipsTheTapStep() {
        val steps = YouTubeMusicRouting.steps(
            directStarted = true,
            ytmTapStarted = false,
            youtubeAvailable = true,
        )
        assertEquals(listOf(MusicPlayStep.DIRECT_PLAY), steps)
    }

    @Test
    fun tapSuccessAnswersPlayingOnYouTubeMusic() {
        val outcome = YouTubeMusicRouting.outcome(
            directStarted = false,
            ytmTapStarted = true,
            youtubeAvailable = true,
            youtubeStarted = false,
        )
        assertEquals(MusicPlayOutcome.PLAYING_ON_YT_MUSIC, outcome)
        val message = YouTubeMusicRouting.message(outcome, arabic = false)
        assertEquals("Playing on YouTube Music.", message)
        assertEquals("شغال على يوتيوب ميوزك.", YouTubeMusicRouting.message(outcome, arabic = true))
    }

    @Test
    fun tapFailureWithYouTubeAvailableFallsBackToYouTube() {
        assertEquals(
            listOf(MusicPlayStep.DIRECT_PLAY, MusicPlayStep.TAP_YTM, MusicPlayStep.YOUTUBE_FALLBACK),
            YouTubeMusicRouting.steps(
                directStarted = false,
                ytmTapStarted = false,
                youtubeAvailable = true,
            ),
        )
        val outcome = YouTubeMusicRouting.outcome(
            directStarted = false,
            ytmTapStarted = false,
            youtubeAvailable = true,
            youtubeStarted = true,
        )
        assertEquals(MusicPlayOutcome.PLAYING_ON_YOUTUBE, outcome)
        val message = YouTubeMusicRouting.message(outcome, arabic = false)
        assertEquals("YouTube Music wouldn't start it, so I'm playing it on YouTube.", message)
        assertEquals(
            "يوتيوب ميوزك ما اشتغل، فشغلته على يوتيوب.",
            YouTubeMusicRouting.message(outcome, arabic = true),
        )
    }

    @Test
    fun fallbackStartingTooGivesUpWithoutTheFallbackStep() {
        assertEquals(
            listOf(MusicPlayStep.DIRECT_PLAY, MusicPlayStep.TAP_YTM),
            YouTubeMusicRouting.steps(
                directStarted = false,
                ytmTapStarted = true,
                youtubeAvailable = true,
            ),
        )
    }

    @Test
    fun everythingFailingIsAFailureMessage() {
        val outcome = YouTubeMusicRouting.outcome(
            directStarted = false,
            ytmTapStarted = false,
            youtubeAvailable = true,
            youtubeStarted = false,
        )
        assertEquals(MusicPlayOutcome.FAILED, outcome)
        val message = YouTubeMusicRouting.message(outcome, arabic = false)
        assertEquals("I opened YouTube Music but couldn't start playback.", message)
        assertEquals(
            "فتحت يوتيوب ميوزك، بس ما قدرت أشغل.",
            YouTubeMusicRouting.message(outcome, arabic = true),
        )
    }

    @Test
    fun noYouTubeInstalledLeavesFailureWithoutFallbackStep() {
        assertEquals(
            listOf(MusicPlayStep.DIRECT_PLAY, MusicPlayStep.TAP_YTM),
            YouTubeMusicRouting.steps(
                directStarted = false,
                ytmTapStarted = false,
                youtubeAvailable = false,
            ),
        )
    }

    @Test
    fun failureIsMarkedUnsuccessful() {
        val outcome = YouTubeMusicRouting.outcome(false, false, false, false)
        assertFalse(outcome == MusicPlayOutcome.PLAYING_ON_YT_MUSIC)
        assertTrue(outcome == MusicPlayOutcome.FAILED)
    }
}
