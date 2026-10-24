package com.jarvis.audio.wake

import kotlin.math.ceil

/**
 * openWakeWord streaming geometry.
 * 16 kHz mono, 80 ms frames, mel (x/10+2), 76-frame windows stepped by 8,
 * and the last 16 embeddings into the wake classifier.
 */
object WakeConfig {
    const val LOG_TAG = "JarvisWake"
    const val ORT_COORDINATE = "com.microsoft.onnxruntime:onnxruntime-android:1.31.0"

    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 1_280
    const val FRAME_SECONDS = FRAME_SAMPLES.toFloat() / SAMPLE_RATE
    const val MEL_BINS = 32
    const val MEL_WINDOW = 76
    const val MEL_STEP = 8
    const val EMBEDDING_DIM = 96
    const val WAKE_FRAMES = 16
    const val MEL_CONTEXT = 160 * 3
    const val MEL_MAX_FRAMES = 10 * 97
    const val FEATURE_MAX = 120
    const val MIN_MEL_SAMPLES = 400
    const val WARMUP_FRAMES = 5

    const val THRESHOLD = 0.35f
    const val SOFT_THRESHOLD = 0.30f
    const val SOFT_WINDOW = 4
    const val SOFT_HITS = 2
    const val TTS_THRESHOLD = 0.7f
    const val TTS_PATIENCE = 2
    const val LOG_SCORE = 0.2f
    const val REFRACTORY_SECONDS = 2f
    const val WATCHDOG_MS = 3_000L
    const val FOLLOW_UP_MS = 7_000L

    const val MEL_ASSET = "wake/melspectrogram.onnx"
    const val EMBEDDING_ASSET = "wake/embedding_model.onnx"
    const val HEY_JARVIS_ASSET = "wake/hey_jarvis_v0.1.onnx"

    /**
     * Official v0.5.1 release assets have no single-word "jarvis" model.
     * hey_jarvis is the only Jarvis model we load.
     */
    const val PLAIN_JARVIS_MODEL = false

    fun transformMel(value: Float): Float = value / 10f + 2f

    /** Matches openWakeWord's `ceil(samples/160 - 3)` mel width. */
    fun melFrames(samples: Int): Int {
        if (samples <= 0) return 0
        return ceil(samples / 160.0 - 3.0).toInt().coerceAtLeast(0)
    }

    fun embeddingCount(melFrames: Int): Int {
        if (melFrames < MEL_WINDOW) return 0
        return (melFrames - MEL_WINDOW) / MEL_STEP + 1
    }
}
