package com.jarvis.provider.groq

/** 16-bit little-endian mono WAV wrapped around the PCM captured by :audio. */
internal object WavPcm {
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        writeAscii(out, 0, "RIFF")
        writeInt(out, 4, 36 + pcm.size)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeInt(out, 16, 16)
        writeShort(out, 20, 1)
        writeShort(out, 22, 1)
        writeInt(out, 24, sampleRate)
        writeInt(out, 28, sampleRate * 2)
        writeShort(out, 32, 2)
        writeShort(out, 34, 16)
        writeAscii(out, 36, "data")
        writeInt(out, 40, pcm.size)
        pcm.copyInto(out, destinationOffset = 44)
        return out
    }

    private fun writeAscii(out: ByteArray, offset: Int, text: String) {
        text.forEachIndexed { index, char -> out[offset + index] = char.code.toByte() }
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = (value shr 8 and 0xff).toByte()
        out[offset + 2] = (value shr 16 and 0xff).toByte()
        out[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun writeShort(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = (value shr 8 and 0xff).toByte()
    }
}
