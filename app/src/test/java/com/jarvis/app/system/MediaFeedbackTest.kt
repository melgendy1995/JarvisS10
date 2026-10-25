package com.jarvis.app.system

import com.jarvis.app.system.MediaFeedback.MediaOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Honesty for pause/resume/skip when nothing is actually playing. */
class MediaFeedbackTest {
    private val none = MediaSessionPicker.STATE_NONE
    private val paused = MediaSessionPicker.STATE_PAUSED
    private val playing = MediaSessionPicker.STATE_PLAYING

    @Test
    fun pauseWithoutAnySessionOrPlaybackSaysNothingIsPlaying() {
        val outcome = MediaFeedback.decide(
            which = "pause",
            arabic = false,
            picked = false,
            stateBefore = none,
            stateAfter = none,
            playingBefore = false,
        )
        assertEquals("Nothing is playing.", (outcome as MediaOutcome.Message).text)
        assertTrue(outcome.success)
    }

    @Test
    fun pauseWithoutPlaybackSpeaksArabicLine() {
        val outcome = MediaFeedback.decide(
            which = "pause",
            arabic = true,
            picked = false,
            stateBefore = none,
            stateAfter = none,
            playingBefore = false,
        )
        assertEquals("ما في شي يشتغل.", (outcome as MediaOutcome.Message).text)
    }

    @Test
    fun pauseOnAnAlreadyPausedSessionIsNothingPlaying() {
        val outcome = MediaFeedback.decide(
            which = "pause",
            arabic = false,
            picked = true,
            stateBefore = paused,
            stateAfter = paused,
            playingBefore = false,
        )
        assertEquals("Paused.", (outcome as MediaOutcome.Message).text)
    }

    @Test
    fun verifiedPauseKeepsPaused() {
        val outcome = MediaFeedback.decide(
            which = "pause",
            arabic = false,
            picked = true,
            stateBefore = playing,
            stateAfter = paused,
            playingBefore = true,
        )
        assertEquals("Paused.", (outcome as MediaOutcome.Message).text)
    }

    @Test
    fun stillPlayingSessionsRefusePause() {
        val outcome = MediaFeedback.decide(
            which = "pause",
            arabic = false,
            picked = true,
            stateBefore = playing,
            stateAfter = playing,
            playingBefore = true,
        )
        assertEquals("I couldn't pause it.", (outcome as MediaOutcome.Message).text)
        assertFalse(outcome.success)
    }

    @Test
    fun keyFallbackWithDetectedPlaybackKeepsTheDefaultLine() {
        assertEquals(
            MediaOutcome.Default,
            MediaFeedback.decide(
                which = "pause",
                arabic = false,
                picked = false,
                stateBefore = none,
                stateAfter = none,
                playingBefore = true,
            ),
        )
    }

    @Test
    fun resumeNextAndPreviousGoQuietWhenNothingIsPlaying() {
        for (which in listOf("resume", "next", "previous")) {
            val outcome = MediaFeedback.decide(
                which = which,
                arabic = false,
                picked = false,
                stateBefore = none,
                stateAfter = none,
                playingBefore = false,
            )
            assertEquals("Nothing is playing.", (outcome as MediaOutcome.Message).text)
        }
    }

    @Test
    fun transportWithDetectedPlaybackKeepsExistingLines() {
        assertEquals(
            MediaOutcome.Default,
            MediaFeedback.decide("next", false, picked = false, stateBefore = none, stateAfter = none, playingBefore = true),
        )
        val resumed = MediaFeedback.decide(
            which = "resume",
            arabic = true,
            picked = true,
            stateBefore = paused,
            stateAfter = playing,
            playingBefore = false,
        )
        assertEquals("كملت.", (resumed as MediaOutcome.Message).text)
    }
}
