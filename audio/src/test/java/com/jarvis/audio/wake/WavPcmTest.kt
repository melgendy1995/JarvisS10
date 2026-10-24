package com.jarvis.audio.wake

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavPcmTest {
    @Test
    fun reads16BitMonoPcm() {
        val pcm = ByteArray(8)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(1000).putShort(-1000).putShort(0)
        val wav = wav(pcm, rate = 16_000, channels = 1)
        val clip = WavPcm.read(wav)
        assertEquals(16_000, clip?.sampleRate)
        assertEquals(4, clip?.samples?.size)
        assertEquals(1000, clip?.samples?.get(1)?.toInt())
    }

    private fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(rate)
        header.putInt(rate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
