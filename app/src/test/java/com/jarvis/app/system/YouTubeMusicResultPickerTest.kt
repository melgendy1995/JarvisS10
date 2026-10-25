package com.jarvis.app.system

import com.jarvis.core.skills.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
