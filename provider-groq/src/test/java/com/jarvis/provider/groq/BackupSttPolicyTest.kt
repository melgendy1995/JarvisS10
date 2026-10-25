package com.jarvis.provider.groq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSttPolicyTest {
    @Test
    fun `non blank recognizer text never transcribes`() {
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "what time is it",
                recognizerFailed = true,
                pcmBytes = 32_000,
                cloudNext = true,
                arabicAuto = true,
                pcmRms = 20,
            ),
        )
    }

    @Test
    fun `auto mode runs whisper on a soft failure with speech level audio`() {
        assertTrue(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = BackupSttPolicy.MIN_PCM_BYTES,
                cloudNext = true,
                arabicAuto = true,
                pcmRms = BackupSttPolicy.MIN_PCM_RMS,
            ),
        )
    }

    @Test
    fun `auto mode does not need the cloud brain in front but still needs whisper ready`() {
        assertTrue(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = 32_000,
                cloudNext = true,
                arabicAuto = true,
                pcmRms = 10,
            ),
        )
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = 32_000,
                cloudNext = false,
                arabicAuto = true,
                pcmRms = 10,
            ),
        )
    }

    @Test
    fun `auto mode skips quiet or short audio`() {
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = 32_000,
                cloudNext = true,
                arabicAuto = true,
                pcmRms = BackupSttPolicy.MIN_PCM_RMS - 1,
            ),
        )
        assertFalse(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = BackupSttPolicy.MIN_PCM_BYTES - 1,
                cloudNext = true,
                arabicAuto = true,
                pcmRms = 20,
            ),
        )
    }

    @Test
    fun `no rms measurement never blocks the auto gate`() {
        assertTrue(
            BackupSttPolicy.shouldTranscribe(
                recognizerText = "",
                recognizerFailed = true,
                pcmBytes = 32_000,
                cloudNext = true,
                arabicAuto = true,
            ),
        )
    }
}
