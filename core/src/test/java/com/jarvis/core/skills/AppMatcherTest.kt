package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val youtube = LaunchableApp(AppMatcher.YOUTUBE, "YouTube")
    private val music = LaunchableApp(AppMatcher.YOUTUBE_MUSIC, "YouTube Music")
    private val notes = LaunchableApp("com.example.notes", "Notes")

    @Test
    fun matchesLabelAliasAndArabic() {
        assertEquals(AppMatcher.YOUTUBE, AppMatcher.best("YouTube", listOf(youtube, notes)).app?.packageName)
        assertEquals(AppMatcher.YOUTUBE, AppMatcher.best("يوتيوب", listOf(youtube)).app?.packageName)
        assertEquals(AppMatcher.YOUTUBE, AppMatcher.best("yt", listOf(youtube, music)).app?.packageName)
        assertEquals(AppMatcher.YOUTUBE_MUSIC, AppMatcher.best("يوتيوب ميوزك", listOf(youtube, music)).app?.packageName)
    }

    @Test
    fun fuzzyContainsAndEditDistance() {
        assertEquals(notes.packageName, AppMatcher.best("note", listOf(notes, youtube)).app?.packageName)
        assertEquals(AppMatcher.YOUTUBE, AppMatcher.best("youtub", listOf(youtube)).app?.packageName)
        assertNull(AppMatcher.best("calculator", listOf(youtube, notes)).app)
    }

    @Test
    fun reportsAmbiguousTies() {
        val left = LaunchableApp("com.example.a", "Music")
        val right = LaunchableApp("com.example.b", "Music")
        val match = AppMatcher.best("Music", listOf(left, right))
        assertNull(match.app)
        assertTrue(match.ambiguous)
    }
}
