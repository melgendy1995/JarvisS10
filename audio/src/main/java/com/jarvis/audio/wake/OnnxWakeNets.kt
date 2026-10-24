package com.jarvis.audio.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * openWakeWord's three ONNX sessions.
 * Mel input is float32 PCM in the int16 range, named by the model (usually "input").
 * The embedding input is [1, 76, 32, 1]. The wake input is [1, 16, 96].
 */
class OnnxWakeNets(context: Context) : WakeNets, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession
    private val embedSession: OrtSession
    private val wakeSession: OrtSession
    private val melInput: String
    private val embedInput: String
    private val wakeInput: String
    private val lock = Any()
    val melBytes: Int
    val embedBytes: Int
    val wakeBytes: Int

    init {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            runCatching { options.addConfigEntry("session.intra_op.allow_spinning", "0") }
            runCatching { options.addConfigEntry("session.inter_op.allow_spinning", "0") }
            val mel = asset(context, WakeConfig.MEL_ASSET)
            val embed = asset(context, WakeConfig.EMBEDDING_ASSET)
            val wake = asset(context, WakeConfig.HEY_JARVIS_ASSET)
            melBytes = mel.size
            embedBytes = embed.size
            wakeBytes = wake.size
            melSession = env.createSession(mel, options)
            embedSession = env.createSession(embed, options)
            wakeSession = env.createSession(wake, options)
            melInput = melSession.inputNames.first()
            embedInput = embedSession.inputNames.first()
            wakeInput = wakeSession.inputNames.first()
            val plain = runCatching {
                context.assets.open("wake/jarvis_v0.1.onnx").close()
            }.isSuccess
            Log.i(
                WakeConfig.LOG_TAG,
                "models ort=${WakeConfig.ORT_COORDINATE} mel=$melBytes embed=$embedBytes " +
                    "hey_jarvis=$wakeBytes melIn=$melInput embedIn=$embedInput wakeIn=$wakeInput " +
                    "plainJarvis=$plain",
            )
            if (!plain) {
                Log.i(
                    WakeConfig.LOG_TAG,
                    "plain Jarvis model is absent. openWakeWord v0.5.1 has hey_jarvis only " +
                        "(alexa, hey_jarvis, hey_mycroft, hey_rhasspy, timer, weather).",
                )
            }
        } finally {
            options.close()
        }
    }

    override fun mel(samples: FloatArray): FloatArray = synchronized(lock) {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong()))
        tensor.use {
            melSession.run(mapOf(melInput to tensor)).use { result ->
                floats(result.get(0) as OnnxTensor).first
            }
        }
    }

    override fun embed(window: FloatArray): FloatArray = synchronized(lock) {
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(window),
            longArrayOf(1, WakeConfig.MEL_WINDOW.toLong(), WakeConfig.MEL_BINS.toLong(), 1),
        )
        tensor.use {
            embedSession.run(mapOf(embedInput to tensor)).use { result ->
                val values = floats(result.get(0) as OnnxTensor).first
                when {
                    values.size == WakeConfig.EMBEDDING_DIM -> values
                    values.size > WakeConfig.EMBEDDING_DIM ->
                        values.copyOfRange(values.size - WakeConfig.EMBEDDING_DIM, values.size)
                    else -> FloatArray(WakeConfig.EMBEDDING_DIM)
                }
            }
        }
    }

    override fun score(features: FloatArray): Float = synchronized(lock) {
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features),
            longArrayOf(1, WakeConfig.WAKE_FRAMES.toLong(), WakeConfig.EMBEDDING_DIM.toLong()),
        )
        tensor.use {
            wakeSession.run(mapOf(wakeInput to tensor)).use { result ->
                floats(result.get(0) as OnnxTensor).first.firstOrNull() ?: 0f
            }
        }
    }

    override fun close() {
        runCatching { melSession.close() }
        runCatching { embedSession.close() }
        runCatching { wakeSession.close() }
    }

    private fun floats(tensor: OnnxTensor): Pair<FloatArray, LongArray> {
        val shape = (tensor.info as TensorInfo).shape
        val buffer = tensor.floatBuffer
        buffer.rewind()
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out to shape
    }

    private fun asset(context: Context, path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }
}
