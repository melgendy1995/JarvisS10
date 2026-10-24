package com.jarvis.audio.wake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV reader. Debug clips are resampled to 16 kHz mono. */
object WavPcm {
    data class Clip(val sampleRate: Int, val samples: ShortArray)

    fun read(bytes: ByteArray): Clip? {
        if (bytes.size < 44) return null
        if (text(bytes, 0, 4) != "RIFF" || text(bytes, 8, 4) != "WAVE") return null
        var rate = 16_000
        var channels = 1
        var bits = 16
        var dataStart = -1
        var dataBytes = 0
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = text(bytes, cursor, 4)
            val size = le32(bytes, cursor + 4)
            val body = cursor + 8
            if (size < 0 || body + size > bytes.size) return null
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    val format = le16(bytes, body)
                    if (format != 1) return null
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataStart = body
                    dataBytes = size
                }
            }
            cursor = body + size + (size and 1)
        }
        if (dataStart < 0 || channels <= 0 || bits != 16 || rate <= 0) return null
        val usable = minOf(dataBytes, bytes.size - dataStart)
        val frameBytes = channels * 2
        if (frameBytes <= 0 || usable < frameBytes) return null
        val frames = usable / frameBytes
        val mono = ShortArray(frames)
        val buffer = ByteBuffer.wrap(bytes, dataStart, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += buffer.short.toInt()
            mono[frame] = (sum / channels).toShort()
        }
        return Clip(rate, if (rate == WakeConfig.SAMPLE_RATE) mono else resample(mono, rate, WakeConfig.SAMPLE_RATE))
    }

    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val src = i.toDouble() * from / to
            val left = src.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val mix = (src - left).toFloat()
            out[i] = (input[left] * (1f - mix) + input[right] * mix).toInt().toShort()
        }
        return out
    }

    private fun text(bytes: ByteArray, offset: Int, length: Int): String {
        return bytes.copyOfRange(offset, offset + length).decodeToString()
    }

    private fun le16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int {
        return le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)
    }
}
