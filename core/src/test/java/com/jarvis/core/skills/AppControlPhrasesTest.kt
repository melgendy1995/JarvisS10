package com.jarvis.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppControlPhrasesTest {
    @Test
    fun playsOnYouTubeInEnglishAndArabic() {
        val english = AppControlPhrases.parse("play Amr Diab on YouTube")
        assertEquals("play", english?.get("action"))
        assertEquals("amr diab", english?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE, english?.get("target"))
        assertEquals("en", english?.get("lang"))

        val quran = AppControlPhrases.parse("play Quran Surah Al-Mulk on YouTube")
        assertEquals("play", quran?.get("action"))
        assertEquals("quran surah al mulk", quran?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE, quran?.get("target"))

        val arabic = AppControlPhrases.parse("شغل عمرو دياب على يوتيوب")
        assertEquals("play", arabic?.get("action"))
        assertEquals("عمرو دياب", arabic?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE, arabic?.get("target"))
        assertEquals("ar", arabic?.get("lang"))
    }

    @Test
    fun plainPlayHasNoTarget() {
        val parsed = AppControlPhrases.parse("play amr diab")
        assertEquals("play", parsed?.get("action"))
        assertEquals("amr diab", parsed?.get("query"))
        assertEquals("", parsed?.get("target"))
    }

    @Test
    fun opensApps() {
        assertEquals("youtube", AppControlPhrases.parse("open YouTube")?.get("target"))
        assertEquals("يوتيوب", AppControlPhrases.parse("افتح يوتيوب")?.get("target"))
        assertEquals("open", AppControlPhrases.parse("please launch Spotify")?.get("action"))
        assertNull(AppControlPhrases.parse("open the timer"))
    }

    @Test
    fun mediaVolumeSkipAndReturn() {
        assertEquals("pause", AppControlPhrases.parse("pause")?.get("media"))
        assertEquals("resume", AppControlPhrases.parse("play")?.get("media"))
        assertEquals("next", AppControlPhrases.parse("next")?.get("media"))
        assertEquals("previous", AppControlPhrases.parse("previous")?.get("media"))
        assertEquals("up", AppControlPhrases.parse("volume up")?.get("volume"))
        assertEquals("down", AppControlPhrases.parse("وطي الصوت")?.get("volume"))
        assertEquals("mute", AppControlPhrases.parse("mute")?.get("volume"))
        assertEquals("unmute", AppControlPhrases.parse("unmute")?.get("volume"))
        assertEquals("percent", AppControlPhrases.parse("set the volume to 40 percent")?.get("unit"))
        assertEquals("40", AppControlPhrases.parse("volume 40%")?.get("amount"))
        assertEquals("skip_ad", AppControlPhrases.parse("skip ad")?.get("action"))
        assertEquals("skip_ad", AppControlPhrases.parse("تخطي الاعلان")?.get("action"))
        assertEquals("skip_ad", AppControlPhrases.parse("تخطى الاعلان")?.get("action"))
        assertEquals("up", AppControlPhrases.parse("صوت أعلى")?.get("volume"))
        assertEquals("return", AppControlPhrases.parse("go back to Jarvis")?.get("action"))
        assertEquals("return", AppControlPhrases.parse("stop")?.get("action"))
        assertEquals("return", AppControlPhrases.parse("ارجع لجارفس")?.get("action"))
        assertEquals("return", AppControlPhrases.parse("ارجع الى جارفس")?.get("action"))
        assertEquals("pause", AppControlPhrases.parse("stop the video")?.get("media"))
    }

    @Test
    fun ignoresUnrelatedSpeech() {
        assertNull(AppControlPhrases.parse("what time is it"))
        assertNull(AppControlPhrases.parse("set a timer for 5 minutes"))
        assertNull(AppControlPhrases.parse(""))
    }
}
