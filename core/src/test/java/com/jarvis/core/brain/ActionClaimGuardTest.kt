package com.jarvis.core.brain

import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard against brains that confirm actions the phone never made. */
class ActionClaimGuardTest {
    private fun typeOf(text: String): ActionClaimGuard.Type? = ActionClaimGuard.detect(text)?.type

    // Replies captured from the device log; each was spoken without any tool having run.
    @Test
    fun shuffleIsCaught() {
        val claim = ActionClaimGuard.detect("Shuffle mode is now active on YouTube Music.")
        assertEquals(ActionClaimGuard.Type.SHUFFLE, claim?.type)
    }

    @Test
    fun noNoNoApologyStopIsCaught() {
        val claim = ActionClaimGuard.detect(
            "I apologize for the misunderstanding, Mahmoud. I have stopped the music.",
        )
        assertEquals(ActionClaimGuard.Type.MEDIA_STOP, claim?.type)
        assertFalse(claim?.arabic ?: true)
    }

    @Test
    fun bareAppNameIsCaughtAsAnOpenClaim() {
        val claim = ActionClaimGuard.detect("Opening YouTube Music now, Mahmoud.")
        assertEquals(ActionClaimGuard.Type.APP_OPEN, claim?.type)
        assertEquals("youtube_music", claim?.app)
    }

    @Test
    fun transportKeyLeakIsCaughtAsAnOpenClaim() {
        val claim = ActionClaimGuard.detect("Open.Opening YouTube Music.")
        assertEquals(ActionClaimGuard.Type.APP_OPEN, claim?.type)
    }

    @Test
    fun confirmedStopIsCaught() {
        val claim = ActionClaimGuard.detect("YouTube Music has been stopped.")
        assertEquals(ActionClaimGuard.Type.MEDIA_STOP, claim?.type)
        assertEquals("youtube_music", claim?.app)
    }

    @Test
    fun stalledPhoneIsCaughtAsAPause() {
        val claim = ActionClaimGuard.detect("I have paused the media for you, Mahmoud. It should be silent now.")
        assertEquals(ActionClaimGuard.Type.MEDIA_STOP, claim?.type)
    }

    // Arabic
    @Test
    fun arabicStopAnnouncementIsCaught() {
        val claim = ActionClaimGuard.detect("تم الايقاف.")
        assertEquals(ActionClaimGuard.Type.MEDIA_STOP, claim?.type)
        assertTrue(claim?.arabic ?: false)
    }

    @Test
    fun arabicFirstPersonStopIsCaught() {
        val claim = ActionClaimGuard.detect("وقفت يوتيوب ميوزك.")
        assertEquals(ActionClaimGuard.Type.MEDIA_STOP, claim?.type)
    }

    @Test
    fun arabicStartAnnouncementIsCaught() {
        val claim = ActionClaimGuard.detect("تم التشغيل.")
        assertEquals(ActionClaimGuard.Type.MEDIA_RESUME, claim?.type)
    }

    @Test
    fun arabicOpenIsCaught() {
        val claim = ActionClaimGuard.detect("فتحت يوتيوب ميوزك الآن.")
        assertEquals(ActionClaimGuard.Type.APP_OPEN, claim?.type)
    }

    @Test
    fun arabicCameraIsCaught() {
        val claim = ActionClaimGuard.detect("الكاميرا شغاله.")
        assertEquals(ActionClaimGuard.Type.CAMERA, claim?.type)
        assertEquals(true, claim?.on)
    }

    @Test
    fun arabicTimerIsCaught() {
        val claim = ActionClaimGuard.detect("ضبطت المؤقت.")
        assertEquals(ActionClaimGuard.Type.TIMER, claim?.type)
    }

    // Honest phrasing follows the claim's language and topic.
    @Test
    fun honestMediaSaysTheExactPhraseInLanguage() {
        val en = ActionClaimGuard.honest(ActionClaimGuard.Claim(ActionClaimGuard.Type.MEDIA_STOP, arabic = false))
        assertTrue(en.contains("pause YouTube Music"))
        assertTrue(en.startsWith("I couldn't"))
        val ar = ActionClaimGuard.honest(ActionClaimGuard.Claim(ActionClaimGuard.Type.MEDIA_STOP, arabic = true))
        assertTrue(ar.contains("وقف يوتيوب ميوزك"))
    }

    @Test
    fun honestOpenCameraAndTimerMatchTheTopic() {
        assertTrue(
            ActionClaimGuard.honest(ActionClaimGuard.Claim(ActionClaimGuard.Type.APP_OPEN, arabic = false))
                .contains("open YouTube Music"),
        )
        assertTrue(
            ActionClaimGuard.honest(ActionClaimGuard.Claim(ActionClaimGuard.Type.CAMERA, arabic = true, on = true))
                .contains("شغل الكاميرا"),
        )
        assertTrue(
            ActionClaimGuard.honest(ActionClaimGuard.Claim(ActionClaimGuard.Type.TIMER, arabic = false))
                .contains("set a timer for 5 minutes"),
        )
    }

    // Rebuilt commands the skill router can claim.
    @Test
    fun canonicalFollowsTheClaim() {
        assertEquals(
            "stop music",
            ActionClaimGuard.canonical(ActionClaimGuard.Claim(ActionClaimGuard.Type.MEDIA_STOP, arabic = false)),
        )
        assertEquals(
            "pause youtube music",
            ActionClaimGuard.canonical(
                ActionClaimGuard.Claim(ActionClaimGuard.Type.MEDIA_STOP, arabic = false, app = "youtube_music"),
            ),
        )
        assertEquals(
            "open youtube music",
            ActionClaimGuard.canonical(
                ActionClaimGuard.Claim(ActionClaimGuard.Type.APP_OPEN, arabic = false, app = "youtube_music"),
            ),
        )
        assertNull(ActionClaimGuard.canonical(ActionClaimGuard.Claim(ActionClaimGuard.Type.SHUFFLE, arabic = false)))
        assertEquals(
            "turn off the camera",
            ActionClaimGuard.canonical(
                ActionClaimGuard.Claim(ActionClaimGuard.Type.CAMERA, arabic = false, on = false),
            ),
        )
    }

    // Non-device statements must never be flagged.
    @Test
    fun generalKnowledgeWithOpenWordsIsNotCaught() {
        assertNull(typeOf("The new store is open till nine, Mahmoud."))
        assertNull(typeOf("The library was opened in 1520 by the Ottoman governor."))
        assertNull(typeOf("Homer's door opened onto Ithaca nothing more."))
        assertNull(typeOf("Ye opened the plate, then the game moved on."))
    }

    @Test
    fun newsStopAndThirdPersonStoriesAreNotCaught() {
        assertNull(typeOf("The match has stopped for the second half."))
        assertNull(typeOf("The game stopped at 90 minutes."))
        assertNull(typeOf("Nuclear talks paused till Monday."))
        assertNull(typeOf("The old song is playing on nostalgic radio again."))
    }

    @Test
    fun weatherAndSmallTalkAreNotCaught() {
        assertNull(typeOf("Rain stopped falling over Riyadh by noon."))
        assertNull(typeOf("I find that funny, Mahmoud."))
        assertNull(typeOf("Tolerance helps: I opened a novel about ships recently."))
    }

    @Test
    fun arabicNewsIsNotCaught() {
        assertNull(typeOf("تم ايقاف العمليات العسكريه صباح اليوم."))
        assertNull(typeOf("تم ايقاف الحرب بعد مفاوضات طويله."))
        assertNull(typeOf("تم التشغيل الجهاز بعد التحديث في الارض"))
        assertNull(typeOf("الاخبار: توقف Traffacking about nothing مبكرا اليوم."))
    }

    @Test
    fun blankAndUnknownStaySafe() {
        assertNull(ActionClaimGuard.detect(""))
        assertNull(ActionClaimGuard.detect("Here is what I know."))
    }

    @Test
    fun anEarnedClaimIsDetectableButTheGuardWaitsForToolRan() {
        // The reply pattern itself looks like a claim; callers skip it when toolRan is true.
        assertTrue(ActionClaimGuard.detect("The flashlight is on.")?.type == ActionClaimGuard.Type.FLASHLIGHT)
    }

    // The Galaxy S10 (ART) rejects Pattern.UNICODE_CHARACTER_CLASS (flag 256) in
    // the initializer, crashing every brain answer. The object must compile on
    // the JVM with only the flags Android accepts.
    @Test
    fun initializesWithAndroidSupportedFlags() {
        assertEquals(0, ActionClaimGuard.flagsForTest() and 256)
        assertEquals(Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE, ActionClaimGuard.flagsForTest())
    }

    @Test
    fun everyRuleCompilesAndMatchesOnAndroidSafeFlags() {
        for ((text, expected) in listOf(
            "تم الايقاف." to true,
            "I have stopped the music." to true,
            "Here is what I know." to false,
        )) {
            val hasClaim = ActionClaimGuard.detect(text) != null
            assertEquals(text, expected, hasClaim)
        }
    }
}
