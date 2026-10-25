package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionPickerTest {
    private fun session(pkg: String, state: Int, active: Long) =
        MediaSessionPicker.Session(pkg, state, active)

    @Test
    fun prefersPlayingSessionWhenNoTarget() {
        val playing = session("youtube", MediaSessionPicker.STATE_PLAYING, 100)
        val newer = session("spotify", MediaSessionPicker.STATE_PAUSED, 300)
        assertEquals(
            playing,
            MediaSessionPicker.choose(listOf(newer, playing), null),
        )
    }

    @Test
    fun fallsBackToMostRecentActive() {
        val older = session("youtube", MediaSessionPicker.STATE_PAUSED, 100)
        val newer = session("spotify", MediaSessionPicker.STATE_PAUSED, 300)
        assertEquals(
            newer,
            MediaSessionPicker.choose(listOf(older, newer), null),
        )
    }

    @Test
    fun namedTargetWinsEvenWhenPaused() {
        val target = session("com.google.android.apps.youtube.music", MediaSessionPicker.STATE_PAUSED, 10)
        val playing = session("youtube", MediaSessionPicker.STATE_PLAYING, 999)
        assertEquals(
            target,
            MediaSessionPicker.choose(listOf(playing, target), "com.google.android.apps.youtube.music"),
        )
    }

    @Test
    fun unknownNamedTargetGivesNothing() {
        val playing = session("youtube", MediaSessionPicker.STATE_PLAYING, 100)
        assertNull(MediaSessionPicker.choose(listOf(playing), "spotify"))
    }

    @Test
    fun jarvisOwnSessionIsExcluded() {
        val own = session(MediaSessionPicker.JARVIS, MediaSessionPicker.STATE_PLAYING, 999)
        val paused = session("youtube", MediaSessionPicker.STATE_PAUSED, 100)
        assertEquals(
            paused,
            MediaSessionPicker.choose(listOf(own, paused), null),
        )
    }

    @Test
    fun jarvisOwnSessionServesNamedTarget() {
        val own = session(MediaSessionPicker.JARVIS, MediaSessionPicker.STATE_PLAYING, 999)
        assertEquals(
            own,
            MediaSessionPicker.choose(listOf(own), MediaSessionPicker.JARVIS),
        )
    }

    @Test
    fun emptyListGivesNothing() {
        assertNull(MediaSessionPicker.choose(emptyList(), null))
        assertNull(MediaSessionPicker.choose(emptyList(), "youtube"))
    }
}
