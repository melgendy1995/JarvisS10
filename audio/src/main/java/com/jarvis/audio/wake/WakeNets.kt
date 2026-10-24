package com.jarvis.audio.wake

/** The three ONNX stages. Shapes are fixed by the openWakeWord models. */
interface WakeNets {
    /** Raw mel, row-major frames of [WakeConfig.MEL_BINS], before the x/10+2 transform. */
    fun mel(samples: FloatArray): FloatArray

    /** One 76×32 window, flattened, in and a [WakeConfig.EMBEDDING_DIM] vector out. */
    fun embed(window: FloatArray): FloatArray

    /** Last 16 embeddings flattened (16×96) to a score in 0..1. */
    fun score(features: FloatArray): Float
}
