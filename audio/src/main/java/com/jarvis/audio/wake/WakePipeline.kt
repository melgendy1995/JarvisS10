package com.jarvis.audio.wake

import kotlin.random.Random

/**
 * Streaming port of openwakeword.utils.AudioFeatures.
 * Each call that completes 1280 new samples appends one embedding and scores it.
 */
class WakePipeline(
    private val nets: WakeNets,
    prime: Boolean = true,
) {
    private val raw = ShortArray(WakeConfig.SAMPLE_RATE * 10)
    private var rawSize = 0
    private var rawPos = 0
    private var accumulated = 0
    private var remainder = ShortArray(0)
    private val mel = ArrayList<FloatArray>(WakeConfig.MEL_MAX_FRAMES + 16)
    private val features = ArrayList<FloatArray>(WakeConfig.FEATURE_MAX)

    init {
        repeat(WakeConfig.MEL_WINDOW) { mel.add(FloatArray(WakeConfig.MEL_BINS) { 1f }) }
        if (prime) primeFromNoise()
    }

    /** Raw model score for this chunk, or null when a full 80 ms frame is not ready. */
    fun accept(chunk: ShortArray): Float? {
        var data = chunk
        if (remainder.isNotEmpty()) {
            data = ShortArray(remainder.size + chunk.size)
            remainder.copyInto(data)
            chunk.copyInto(data, remainder.size)
            remainder = ShortArray(0)
        }
        if (accumulated + data.size >= WakeConfig.FRAME_SAMPLES) {
            val rem = (accumulated + data.size) % WakeConfig.FRAME_SAMPLES
            if (rem != 0) {
                val even = data.copyOf(data.size - rem)
                pushRaw(even)
                accumulated += even.size
                remainder = data.copyOfRange(data.size - rem, data.size)
            } else {
                pushRaw(data)
                accumulated += data.size
            }
        } else {
            accumulated += data.size
            pushRaw(data)
            return null
        }
        if (accumulated < WakeConfig.FRAME_SAMPLES || accumulated % WakeConfig.FRAME_SAMPLES != 0) {
            return null
        }
        val processed = accumulated
        val chunks = processed / WakeConfig.FRAME_SAMPLES
        appendMelAndEmbeddings(processed, chunks)
        accumulated = 0
        trim()
        var max = Float.NEGATIVE_INFINITY
        var any = false
        for (i in chunks - 1 downTo 0) {
            val vector = lastFeatures(skip = i) ?: continue
            val score = nets.score(vector)
            if (!any || score > max) max = score
            any = true
        }
        return if (any) max else null
    }

    private fun primeFromNoise() {
        val noise = FloatArray(WakeConfig.SAMPLE_RATE * 4) {
            Random.nextInt(-1000, 1001).toFloat()
        }
        val spec = nets.mel(noise)
        val frames = spec.size / WakeConfig.MEL_BINS
        val windows = WakeConfig.embeddingCount(frames)
        val embeddings = ArrayList<FloatArray>(windows)
        var index = 0
        while (index + WakeConfig.MEL_WINDOW <= frames) {
            embeddings.add(nets.embed(windowAt(spec, index)))
            index += WakeConfig.MEL_STEP
        }
        features.clear()
        features.addAll(embeddings)
    }

    private fun appendMelAndEmbeddings(samples: Int, chunks: Int) {
        val take = lastRaw(samples + WakeConfig.MEL_CONTEXT)
        if (take.size < WakeConfig.MIN_MEL_SAMPLES) return
        val spec = nets.mel(take)
        val frames = spec.size / WakeConfig.MEL_BINS
        for (frame in 0 until frames) {
            val row = FloatArray(WakeConfig.MEL_BINS)
            for (bin in 0 until WakeConfig.MEL_BINS) {
                row[bin] = WakeConfig.transformMel(spec[frame * WakeConfig.MEL_BINS + bin])
            }
            mel.add(row)
        }
        for (i in chunks - 1 downTo 0) {
            val window = melWindow(skipFrames = WakeConfig.MEL_STEP * i) ?: continue
            features.add(nets.embed(window))
        }
    }

    private fun melWindow(skipFrames: Int): FloatArray? {
        val end = mel.size - skipFrames
        val start = end - WakeConfig.MEL_WINDOW
        if (start < 0 || end > mel.size) return null
        val out = FloatArray(WakeConfig.MEL_WINDOW * WakeConfig.MEL_BINS)
        for (frame in 0 until WakeConfig.MEL_WINDOW) {
            mel[start + frame].copyInto(out, frame * WakeConfig.MEL_BINS)
        }
        return out
    }

    private fun lastFeatures(skip: Int): FloatArray? {
        val end = features.size - skip
        val start = end - WakeConfig.WAKE_FRAMES
        if (start < 0 || end > features.size) return null
        val out = FloatArray(WakeConfig.WAKE_FRAMES * WakeConfig.EMBEDDING_DIM)
        for (frame in 0 until WakeConfig.WAKE_FRAMES) {
            val row = features[start + frame]
            val copy = minOf(row.size, WakeConfig.EMBEDDING_DIM)
            row.copyInto(out, frame * WakeConfig.EMBEDDING_DIM, 0, copy)
        }
        return out
    }

    private fun windowAt(spec: FloatArray, frame: Int): FloatArray {
        val out = FloatArray(WakeConfig.MEL_WINDOW * WakeConfig.MEL_BINS)
        for (row in 0 until WakeConfig.MEL_WINDOW) {
            val src = (frame + row) * WakeConfig.MEL_BINS
            for (bin in 0 until WakeConfig.MEL_BINS) {
                out[row * WakeConfig.MEL_BINS + bin] = WakeConfig.transformMel(spec[src + bin])
            }
        }
        return out
    }

    private fun pushRaw(samples: ShortArray) {
        for (sample in samples) {
            raw[rawPos] = sample
            rawPos = (rawPos + 1) % raw.size
            if (rawSize < raw.size) rawSize++
        }
    }

    private fun lastRaw(count: Int): FloatArray {
        val n = minOf(count, rawSize)
        val out = FloatArray(n)
        var start = rawPos - n
        if (start < 0) start += raw.size
        for (i in 0 until n) {
            out[i] = raw[(start + i) % raw.size].toFloat()
        }
        return out
    }

    private fun trim() {
        while (mel.size > WakeConfig.MEL_MAX_FRAMES) mel.removeAt(0)
        while (features.size > WakeConfig.FEATURE_MAX) features.removeAt(0)
    }
}
