package com.jarvis.app.system

import com.jarvis.core.skills.AppControlPhrases
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReturnTest {
    @Test fun playAfterLaunchReturns() {
        assertTrue(ForegroundReturn.wantsReturn(AppControlPhrases.PLAY, "com.google.android.apps.youtube.music", false))
    }

    @Test fun openAppDoesNotReturn() {
        assertFalse(ForegroundReturn.wantsReturn(AppControlPhrases.OPEN, "com.whatsapp", false))
    }

    @Test fun nothingLaunchedNoReturn() {
        assertFalse(ForegroundReturn.wantsReturn(AppControlPhrases.PLAY, null, false))
    }

    @Test fun alreadyInFrontNoReturn() {
        assertFalse(ForegroundReturn.wantsReturn(AppControlPhrases.PLAY, "x", true))
        assertFalse(ForegroundReturn.allowReturn(jarvisInFront = true, nowMs = 10_000, lastReturnMs = 0))
    }

    @Test fun rapidSecondReturnIsDropped() {
        assertFalse(ForegroundReturn.allowReturn(false, nowMs = 11_000, lastReturnMs = 10_000))
        assertTrue(ForegroundReturn.allowReturn(false, nowMs = 13_000, lastReturnMs = 10_000))
        assertTrue(ForegroundReturn.allowReturn(false, nowMs = 5, lastReturnMs = 0))
    }

    @Test fun metadataMatchEnglish() {
        assertTrue(ForegroundReturn.metadataMatches("Fairuz", "Kifak Inta", "Fairuz", null))
        assertFalse(ForegroundReturn.metadataMatches("Fairuz", "Bohemian Rhapsody", "Queen", null))
    }

    @Test fun metadataMatchArabicNormalized() {
        assertTrue(ForegroundReturn.metadataMatches("فيروز", "نسم علينا الهوى", "فيروز", null))
        assertTrue(ForegroundReturn.metadataMatches("أم كلثوم", "انت عمري", "ام كلثوم", null))
    }

    @Test fun emptyMetadataNoMatch() {
        assertFalse(ForegroundReturn.metadataMatches("Fairuz", null, null, null))
    }
}
