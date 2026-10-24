package com.jarvis.app.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEndTest {
    @Test
    fun aUserPauseIsNotTheVideoEnding() {
        assertFalse(playbackEnded(sawStarted = true, started = false, paused = false, userPaused = true))
    }

    @Test
    fun aReportedPauseIsNotTheVideoEnding() {
        assertFalse(playbackEnded(sawStarted = true, started = false, paused = true, userPaused = false))
    }

    @Test
    fun aPlayerThatVanishesAfterStartingHasEnded() {
        assertTrue(playbackEnded(sawStarted = true, started = false, paused = false, userPaused = false))
    }

    @Test
    fun nothingEndsBeforePlaybackStarts() {
        assertFalse(playbackEnded(sawStarted = false, started = false, paused = false, userPaused = false))
    }
}
