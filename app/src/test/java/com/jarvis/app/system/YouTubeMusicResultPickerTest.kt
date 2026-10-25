package com.jarvis.app.system

import com.jarvis.core.skills.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YT Music search-page picking, fed the strings the app publishes. */
class YouTubeMusicResultPickerTest {
    private fun node(
        text: String = "",
        desc: String = "",
        clickable: Boolean = false,
        top: Int = 0,
        height: Int = 100,
    ) = UiNode(
        text = text,
        desc = desc,
        viewId = "",
        clickable = clickable,
        left = 0,
        top = top,
        right = 1080,
        bottom = top + height,
    )

    @Test
    fun emptyMeansNull() {
        assertNull(YouTubeMusicResultPicker.bestPlayable(emptyList()))
    }

    @Test
    fun picksThePlayButtonOverNavItems() {
        val nav = node(desc = "Home", clickable = true, top = 2250, height = 130)
        val otherNav = node(desc = "Samples", clickable = true, top = 2280, height = 130)
        val play = node(desc = "Play", clickable = true, top = 420, height = 120)
        val row = node(text = "Waka Waka • Song • Shakira", clickable = true, top = 700, height = 140)
        assertEquals(play, YouTubeMusicResultPicker.bestPlayable(listOf(nav, otherNav, row, play)))
    }

    @Test
    fun picksAPlayLabelWithTheSongName() {
        val play = node(desc = "Play Waka Waka (This Time for Africa)", clickable = true, top = 400, height = 120)
        val row = node(text = "Waka Waka • Song • Shakira", clickable = true, top = 700, height = 140)
        assertEquals(play, YouTubeMusicResultPicker.bestPlayable(listOf(row, play)))
    }

    @Test
    fun picksTheArabicPlayLabel() {
        val play = node(desc = "تشغيل", clickable = true, top = 400, height = 120)
        val row = node(text = "حبيبي يا نور العين • أغنية • عمرو دياب", clickable = true, top = 700, height = 140)
        assertEquals(play, YouTubeMusicResultPicker.bestPlayable(listOf(row, play)))
    }

    @Test
    fun withoutAPlayButtonPicksTheTopmostSongRow() {
        val top = node(text = "Waka Waka • Song • Shakira", clickable = true, top = 600, height = 140)
        val lower = node(text = "La La La • Song • Shakira", clickable = true, top = 750, height = 140)
        val video = node(text = "Waka Waka • Video • Shakira", clickable = true, top = 900, height = 140)
        assertEquals(top, YouTubeMusicResultPicker.bestPlayable(listOf(video, lower, top)))
    }

    @Test
    fun picksASongRowWhenOnlyRowsExist() {
        val row = node(text = "Waka Waka (This Time for Africa) • Song • Shakira", clickable = true, top = 650, height = 140)
        assertEquals(row, YouTubeMusicResultPicker.bestPlayable(listOf(row)))
    }

    @Test
    fun ignoresFilterChipsAndSearchBox() {
        val search = node(desc = "Search", clickable = true, top = 0, height = 120)
        val chipSongs = node(desc = "Songs", clickable = true, top = 130, height = 90)
        val chipVideos = node(desc = "Videos", clickable = true, top = 130, height = 90)
        assertNull(YouTubeMusicResultPicker.bestPlayable(listOf(search, chipSongs, chipVideos)))
    }

    @Test
    fun ignoresNavBarAndToolbarWhenOnlyThoseExist() {
        val search = node(desc = "Search", clickable = true, top = 0, height = 120)
        val nav = node(desc = "Explore", clickable = true, top = 2250, height = 130)
        val uploads = node(desc = "Uploads", clickable = true, top = 130, height = 90)
        assertNull(YouTubeMusicResultPicker.bestPlayable(listOf(search, nav, uploads)))
    }

    @Test
    fun ignoresUnclickableRows() {
        val label = node(text = "Waka Waka • Song • Shakira", clickable = false, top = 600, height = 140)
        assertNull(YouTubeMusicResultPicker.bestPlayable(listOf(label)))
    }

    @Test
    fun arabicResultsForTheNewQueryAreReady() {
        val fresh = listOf(
            node(text = "سورة الكهف - ياسر الدوسري • أغنية", clickable = true, top = 500),
        )
        val stale = listOf(
            node(text = "Coldplay Yellow • Song", clickable = true, top = 500),
        )
        assertTrue(YouTubeMusicResultPicker.readyFor(fresh, "سورة الكهف", stale))
    }

    @Test
    fun leftoverResultsOfThePreviousSearchAreNotReady() {
        val stale = listOf(
            node(text = "Coldplay Yellow • Song", clickable = true, top = 500),
            node(text = "Coldplay Yellow (Audio) • Video", clickable = true, top = 680),
        )
        assertFalse(YouTubeMusicResultPicker.readyFor(stale, "سورة الكهف", stale))
    }

    @Test
    fun englishResultsMatchTheirQueryToken() {
        val fresh = listOf(
            node(text = "Yellow - Coldplay • Song", clickable = true, top = 500),
        )
        assertTrue(YouTubeMusicResultPicker.readyFor(fresh, "yellow coldplay", emptyList()))
    }

    @Test
    fun anEmptyPreviousSnapshotRequiresTheQueryToken() {
        val stale = listOf(
            node(text = "Coldplay Yellow • Song", clickable = true, top = 500),
        )
        assertFalse(YouTubeMusicResultPicker.readyFor(stale, "سورة الكهف", emptyList()))
        assertTrue(YouTubeMusicResultPicker.readyFor(stale, "yellow coldplay", emptyList()))
    }

    @Test
    fun changedRowsWithoutAQueryTokenStillCountAsFresh() {
        val previous = listOf(
            node(text = "Coldplay Yellow • Song", clickable = true, top = 500),
        )
        val changed = listOf(
            node(text = "Amr Diab • Song", clickable = true, top = 500),
        )
        assertTrue(YouTubeMusicResultPicker.readyFor(changed, " غير معروف ", previous))
    }

    @Test
    fun navRowsNeverCountAsResults() {
        val nav = listOf(
            node(desc = "Search", clickable = true, top = 0),
            node(desc = "Explore", clickable = true, top = 2200),
        )
        assertFalse(YouTubeMusicResultPicker.readyFor(nav, "سورة الكهف", emptyList()))
    }

    @Test
    fun stopWordsDoNotMisleadTheTokenCheck() {
        val tokens = YouTubeMusicResultPicker.significantTokens("play surah? الكهف please")
        assertFalse(tokens.contains("play"))
        assertFalse(tokens.contains("please"))
        assertTrue(tokens.contains("الكهف"))
    }
}
