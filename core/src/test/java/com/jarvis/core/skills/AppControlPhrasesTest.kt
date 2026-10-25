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

    @Test
    fun shuffleArtistPlaysOnYouTubeMusic() {
        val english = AppControlPhrases.parse("shuffle fairuz")
        assertEquals("play", english?.get("action"))
        assertEquals("fairuz", english?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, english?.get("target"))
        assertEquals("en", english?.get("lang"))

        val the = AppControlPhrases.parse("shuffle the beatles")
        assertEquals("beatles", the?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, the?.get("target"))
    }

    @Test
    fun bareShuffleStaysUnmatched() {
        assertNull(AppControlPhrases.parse("shuffle"))
        assertNull(AppControlPhrases.parse("please shuffle"))
    }

    @Test
    fun bareArabicPlayVerbRoutesToYouTubeMusic() {
        val play = AppControlPhrases.parse("شغل فيروز")
        assertEquals("play", play?.get("action"))
        assertEquals("فيروز", play?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, play?.get("target"))
        assertEquals("ar", play?.get("lang"))

        val shadda = AppControlPhrases.parse("شغّل فيروز")
        assertEquals("play", shadda?.get("action"))
        assertEquals("فيروز", shadda?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, shadda?.get("target"))
    }

    @Test
    fun arabicPlayOnYouTubeMusic() {
        val play = AppControlPhrases.parse("شغل سورة الكهف على يوتيوب ميوزك")
        assertEquals("play", play?.get("action"))
        assertEquals("سوره الكهف", play?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, play?.get("target"))
        assertEquals("ar", play?.get("lang"))
    }

    @Test
    fun arabicPlayVerbVariants() {
        val shaghli = AppControlPhrases.parse("شغلي سورة الكهف في يوتيوب موسيقى")
        assertEquals("play", shaghli?.get("action"))
        assertEquals("سوره الكهف", shaghli?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, shaghli?.get("target"))

        val hatli = AppControlPhrases.parse("حطلي عمرو دياب على يوتيوب ميوزيك")
        assertEquals("play", hatli?.get("action"))
        assertEquals("عمرو دياب", hatli?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, hatli?.get("target"))

        val abdha = AppControlPhrases.parse("ابغى اسمع سورة يس على يوتيوب ميوزك")
        assertEquals("play", abdha?.get("action"))
        assertEquals("سوره يس", abdha?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, abdha?.get("target"))

        val mumkin = AppControlPhrases.parse("ممكن تشغل فيروز على YouTube Music")
        assertEquals("play", mumkin?.get("action"))
        assertEquals("فيروز", mumkin?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, mumkin?.get("target"))
        assertEquals("ar", mumkin?.get("lang"))
    }

    @Test
    fun diacriticArabicStillPlays() {
        val tashkeel = AppControlPhrases.parse("شغّل سورةُ الكهفِ على يُوتيوب مِيوزك")
        assertEquals("play", tashkeel?.get("action"))
        assertEquals("سوره الكهف", tashkeel?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, tashkeel?.get("target"))
    }

    @Test
    fun arabicPlayAppFirstOrder() {
        val afterOn = AppControlPhrases.parse("على يوتيوب ميوزك شغل سورة الكهف")
        assertEquals("play", afterOn?.get("action"))
        assertEquals("سوره الكهف", afterOn?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, afterOn?.get("target"))

        val withAnd = AppControlPhrases.parse("افتح يوتيوب ميوزك وشغل سورة الكهف")
        assertEquals("play", withAnd?.get("action"))
        assertEquals("سوره الكهف", withAnd?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, withAnd?.get("target"))
    }

    @Test
    fun appOnlyArabicMeansOpen() {
        val open = AppControlPhrases.parse("افتح يوتيوب ميوزك")
        assertEquals("open", open?.get("action"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, open?.get("target"))

        val shaghil = AppControlPhrases.parse("شغل يوتيوب ميوزك")
        assertEquals("open", shaghil?.get("action"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, shaghil?.get("target"))
    }

    @Test
    fun englishPlaysStillParseAsBefore() {
        val coldplay = AppControlPhrases.parse("play Coldplay Yellow on youtube music")
        assertEquals("play", coldplay?.get("action"))
        assertEquals("coldplay yellow", coldplay?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, coldplay?.get("target"))

        val quran = AppControlPhrases.parse("Play Quran on YouTube music")
        assertEquals("play", quran?.get("action"))
        assertEquals("quran", quran?.get("query"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, quran?.get("target"))
    }

    @Test
    fun mediaPhrasesWithAppNames() {
        val stopApp = AppControlPhrases.parse("Stop YouTube music")
        assertEquals("media", stopApp?.get("action"))
        assertEquals("pause", stopApp?.get("media"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, stopApp?.get("target"))

        assertEquals(
            AppControlPhrases.TARGET_YOUTUBE_MUSIC,
            AppControlPhrases.parse("pause YouTube Music")?.get("target"),
        )
        assertEquals("pause", AppControlPhrases.parse("stop music")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("stop it")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("still not stopped")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("it's still playing")?.get("media"))
        assertEquals("resume", AppControlPhrases.parse("resume youtube music")?.get("media"))
        assertEquals("resume", AppControlPhrases.parse("continue the music")?.get("media"))
        assertEquals("resume", AppControlPhrases.parse("play again")?.get("media"))
        assertEquals("next", AppControlPhrases.parse("next on youtube music")?.get("media"))
        assertEquals("next", AppControlPhrases.parse("skip")?.get("media"))
        assertEquals("previous", AppControlPhrases.parse("previous")?.get("media"))
    }

    @Test
    fun arabicMediaPhrases() {
        assertEquals("pause", AppControlPhrases.parse("وقف")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("اوقف")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("اسكت")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("وقف الموسيقي")?.get("media"))
        assertEquals("pause", AppControlPhrases.parse("وقف الاغنيه")?.get("media"))

        val appPause = AppControlPhrases.parse("وقف يوتيوب ميوزك")
        assertEquals("pause", appPause?.get("media"))
        assertEquals(AppControlPhrases.TARGET_YOUTUBE_MUSIC, appPause?.get("target"))

        assertEquals("resume", AppControlPhrases.parse("كمل")?.get("media"))
        assertEquals("resume", AppControlPhrases.parse("كمل الموسيقي")?.get("media"))
        assertEquals(
            AppControlPhrases.TARGET_YOUTUBE_MUSIC,
            AppControlPhrases.parse("كمل يوتيوب ميوزك")?.get("target"),
        )
        assertEquals("resume", AppControlPhrases.parse("رجع شغل")?.get("media"))
        assertEquals("next", AppControlPhrases.parse("التالي")?.get("media"))
        assertEquals("next", AppControlPhrases.parse("الاغنيه اللي بعدها")?.get("media"))
        assertEquals("previous", AppControlPhrases.parse("اللي قبلها")?.get("media"))
    }

    @Test
    fun bareStopStillReturnsToJarvis() {
        assertEquals("return", AppControlPhrases.parse("stop")?.get("action"))
        assertEquals("return", AppControlPhrases.parse("ارجع")?.get("action"))
        assertNull(AppControlPhrases.parse("افتح الكاميرا"))
    }
}
