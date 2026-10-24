package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUiTest {
    private val width = 1440
    private val height = 2733

    @Test
    fun picksTheRealVideoAndSkipsSponsoredChannelAndShorts() {
        val sponsored = row(
            desc = "Sponsored - سلطان العماني - 4 minutes, 8 seconds - play video",
            top = 335,
            bottom = 1424,
        )
        val channel = row(desc = "Go to channel Amr Diab", top = 1500, bottom = 1900, left = 42, right = 294)
        val shorts = row(desc = "Shorts funny clip 0:20", top = 400, bottom = 900, left = 40, right = 500)
        val mix = row(desc = "Mix - Amr Diab - Curated by YouTube - 50 videos", top = 329, bottom = 1340)
        val video = row(
            desc = "Amr Diab - Khalik Maaya - 3 minutes, 53 seconds - Go to channel Rotana - 141 million views - 10 years ago - play video",
            top = 1424,
            bottom = 2505,
        )
        val nodes = listOf(sponsored, channel, shorts, mix, video)
        assertEquals(video, YouTubeUi.firstVideo(nodes, width, height))
        assertEquals(video, YouTubeUi.firstPlayable(nodes, width, height))
        assertEquals(mix, YouTubeUi.firstMix(nodes, width, height))
    }

    @Test
    fun miniPlayerIsNotTheWatchPage() {
        val mini = UiNode(
            text = "",
            desc = "Video player",
            viewId = "com.google.android.youtube:id/watch_player",
            clickable = false,
            left = 720,
            top = 2287,
            right = 1412,
            bottom = 2676,
        )
        val full = mini.copy(left = 0, top = 139, right = 1440, bottom = 1242)
        assertFalse(YouTubeUi.isFullPlayer(listOf(mini), width))
        assertTrue(YouTubeUi.isFullPlayer(listOf(full), width))
        assertTrue(YouTubeUi.isResults(listOf(row(desc = "", top = 167, bottom = 279).copy(viewId = "com.google.android.youtube:id/search_query", clickable = true, text = "Amr Diab"))))
    }

    @Test
    fun skipButtonUsesTheModernIdAndIgnoresTheCountdown() {
        val ready = UiNode(
            text = "Skip",
            desc = "Skip ad",
            viewId = "com.google.android.youtube:id/modern_skip_ad_button",
            clickable = true,
            left = 1188,
            top = 1363,
            right = 1440,
            bottom = 1531,
        )
        val waiting = ready.copy(text = "Skip in 5", desc = "")
        assertNotNull(YouTubeUi.skipButton(listOf(ready)))
        assertNull(YouTubeUi.skipButton(listOf(waiting)))
        assertTrue(YouTubeUi.isSkipAd("", "", ready.viewId))
    }

    @Test
    fun playerControls() {
        val pause = UiNode(
            text = "",
            desc = "Pause video",
            viewId = "com.google.android.youtube:id/player_control_play_pause_replay_button",
            clickable = true,
            left = 600,
            top = 600,
            right = 800,
            bottom = 800,
        )
        val next = pause.copy(desc = "Next video", viewId = "com.google.android.youtube:id/next_button")
        assertEquals(pause, YouTubeUi.control(listOf(pause, next), "pause"))
        assertEquals(next, YouTubeUi.control(listOf(pause, next), "next"))
        assertTrue(YouTubeUi.controlAlready(listOf(pause), "play"))
        assertFalse(YouTubeUi.controlAlready(listOf(pause), "pause"))
    }

    @Test
    fun landscapeShelfCardIsAVideoAndAPartialCardIsNot() {
        val screenWidth = 3040
        val screenHeight = 1440
        val ad = row(
            desc = "Sponsored - Effortlessly Increase Focus - Visit site",
            top = 280,
            bottom = 1068,
            left = 139,
            right = 2733,
        )
        val card = row(
            desc = "The Haunted Manor - 2 minutes, 7 seconds - Lucid Keys - Topic - 50 thousand views - 3 weeks ago - play video",
            top = 979,
            bottom = 1272,
            left = 209,
            right = 769,
        )
        val clipped = row(
            desc = "Scarecrow - 1 minute, 46 seconds - play video",
            top = 979,
            bottom = 1272,
            left = 2449,
            right = 2733,
        )
        assertEquals(card, YouTubeUi.firstVideo(listOf(ad, card, clipped), screenWidth, screenHeight))
        assertTrue(YouTubeUi.hasLoadedRows(listOf(ad)))
        assertFalse(YouTubeUi.hasLoadedRows(listOf(row(desc = "lo fi girl", top = 112, bottom = 252, left = 363, right = 2536))))
    }

    @Test
    fun landscapePlayerBesideTheQueueCountsAsTheWatchPage() {
        val player = UiNode(
            text = "",
            desc = "Video player",
            viewId = "com.google.android.youtube:id/watch_player",
            clickable = false,
            left = 0,
            top = 80,
            right = 1800,
            bottom = 1000,
        )
        assertTrue(YouTubeUi.isFullPlayer(listOf(player), 3040))
    }

    private fun row(
        desc: String,
        top: Int,
        bottom: Int,
        left: Int = 0,
        right: Int = 1440,
    ) = UiNode(
        text = "",
        desc = desc,
        viewId = "",
        clickable = true,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )
}
