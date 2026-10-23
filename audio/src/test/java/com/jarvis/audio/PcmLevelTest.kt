package com.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLevelTest {
    @Test
    fun silenceIsZero() {
        assertEquals(0f, PcmLevel.rms16(ByteArray(320)), 0.0001f)
    }

    @Test
    fun fullScaleIsCapped() {
        val pcm = ByteArray(4)
        pcm[0] = 0xff.toByte()
        pcm[1] = 0x7f.toByte()
        pcm[2] = 0x00
        pcm[3] = 0x80.toByte()
        assertEquals(1f, PcmLevel.rms16(pcm), 0.0001f)
    }

    @Test
    fun quietSpeechIsAudibleButNotFull() {
        val pcm = ByteArray(200)
        for (i in pcm.indices step 2) {
            pcm[i] = 0x00
            pcm[i + 1] = 0x10
        }
        val level = PcmLevel.rms16(pcm)
        assertTrue(level > 0.05f)
        assertTrue(level < 1f)
    }
}
