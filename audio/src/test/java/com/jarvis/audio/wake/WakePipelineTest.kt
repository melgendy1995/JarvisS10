package com.jarvis.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePipelineTest {
    @Test
    fun melAndEmbeddingShapesMatchOpenWakeWord() {
        assertEquals(5, WakeConfig.melFrames(1280))
        assertEquals(8, WakeConfig.melFrames(1760))
        assertEquals(397, WakeConfig.melFrames(64_000))
        assertEquals(41, WakeConfig.embeddingCount(397))
        assertEquals(1, WakeConfig.embeddingCount(76))
        assertEquals(0, WakeConfig.embeddingCount(75))
        assertEquals(3f, WakeConfig.transformMel(10f), 0.0001f)
        assertEquals(2f, WakeConfig.transformMel(0f), 0.0001f)
        assertFalse(WakeConfig.PLAIN_JARVIS_MODEL)
    }

    @Test
    fun streamingWindowIs76By32AndWakeInputIs16By96() {
        val fake = FakeNets()
        val pipeline = WakePipeline(fake, prime = false)
        val frame = ShortArray(WakeConfig.FRAME_SAMPLES)
        var scored: FloatArray? = null
        repeat(16) {
            pipeline.accept(frame)
            if (fake.scored.isNotEmpty()) scored = fake.scored.last()
        }
        assertEquals(listOf(5), fake.melFrames.distinct().take(1))
        assertTrue(fake.melFrames.drop(1).all { it == 8 })
        assertEquals(16, fake.windows.size)
        assertTrue(fake.windows.all { it == WakeConfig.MEL_WINDOW * WakeConfig.MEL_BINS })
        val features = scored
        assertEquals(WakeConfig.WAKE_FRAMES * WakeConfig.EMBEDDING_DIM, features?.size)
        assertEquals(1f, features!![0], 0.001f)
        assertEquals(16f, features[15 * WakeConfig.EMBEDDING_DIM], 0.001f)
        val firstWindow = fake.windowValues.first()
        assertTrue(firstWindow.any { it == 1f })
        assertTrue(firstWindow.any { it == 2f })
    }

    private class FakeNets : WakeNets {
        val melFrames = ArrayList<Int>()
        val windows = ArrayList<Int>()
        val windowValues = ArrayList<FloatArray>()
        val scored = ArrayList<FloatArray>()

        override fun mel(samples: FloatArray): FloatArray {
            val frames = WakeConfig.melFrames(samples.size)
            melFrames.add(frames)
            return FloatArray(frames * WakeConfig.MEL_BINS)
        }

        override fun embed(window: FloatArray): FloatArray {
            windows.add(window.size)
            windowValues.add(window.copyOf())
            val id = windows.size.toFloat()
            return FloatArray(WakeConfig.EMBEDDING_DIM) { index -> if (index == 0) id else 0f }
        }

        override fun score(features: FloatArray): Float {
            scored.add(features.copyOf())
            return 0.9f
        }
    }
}
