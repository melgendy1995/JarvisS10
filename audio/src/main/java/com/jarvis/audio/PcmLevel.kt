package com.jarvis.audio

import kotlin.math.sqrt

/** RMS of 16-bit little-endian mono PCM, scaled so speech sits near the middle of 0..1. */
object PcmLevel {
    fun rms16(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed.toDouble() * signed.toDouble()
            count++
            index += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / 32768.0
        return (rms * 3.0).toFloat().coerceIn(0f, 1f)
    }
}
