package com.jarvis.orb

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

internal class OrbRenderer : GLSurfaceView.Renderer {
    @Volatile var targetState: OrbState = OrbState.IDLE
    @Volatile var audioLevel: Float = 0f
    @Volatile var targetAccent: BrainAccent = BrainAccent.PHONE

    /** 15 fps idle, 30 fps while a state is active. The view posts [requestRender] on this cadence. */
    val frameDelayMs: Long
        get() = if (targetState == OrbState.IDLE) 67L else 33L

    private var program = 0
    private var vbo = 0
    private var pointCount = 0
    private var width = 1
    private var height = 1
    private var maxPointSize = 128f
    private var startNs = 0L
    private var lastNs = 0L
    private var levelSmooth = 0f
    private val current = OrbState.IDLE.target()
    private var accentR = BrainAccent.PHONE.red
    private var accentG = BrainAccent.PHONE.green
    private var accentB = BrainAccent.PHONE.blue
    private val uniform = HashMap<String, Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(OrbShaders.vertex, OrbShaders.fragment)
        if (program == 0) return
        val names = arrayOf(
            "uT", "uR", "uSw", "uN", "uSc", "uLvl", "uLo", "uHi", "uPs", "uAsp", "uDpr",
            "uB", "uBloom", "uFlat", "uFlick", "uMaxPs", "uDrift", "uC", "uBase", "uCm", "uTint",
        )
        GLES30.glUseProgram(program)
        for (name in names) {
            uniform[name] = GLES30.glGetUniformLocation(program, name)
        }
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        val points = buildPointCloud()
        pointCount = points.size / 4
        val native = ByteBuffer.allocateDirect(points.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        native.put(points).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, points.size * 4, native, GLES30.GL_STATIC_DRAW)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_DITHER)
        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        if (range[1] > 1f) maxPointSize = range[1]
        startNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, this.width, this.height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (program == 0 || pointCount == 0) return

        val now = System.nanoTime()
        if (startNs == 0L) {
            startNs = now
            lastNs = now
        }
        val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNs = now
        val t = (now - startNs) / 1_000_000_000f
        val k = (1f - exp(-dt * 2.2f)).coerceIn(0f, 1f)

        val target = targetState.target()
        current.lerpToward(target, k)
        val accent = targetAccent
        accentR += (accent.red - accentR) * k
        accentG += (accent.green - accentG) * k
        accentB += (accent.blue - accentB) * k
        val desired = audioLevel.coerceIn(0f, 1f)
        levelSmooth += (desired - levelSmooth) * min(1f, dt * 14f)
        val level = levelSmooth * current.agitation
        val breathe = current.brightness * if (targetState == OrbState.IDLE) {
            0.8f + 0.2f * sin(t * 0.9f)
        } else {
            1f
        }

        GLES30.glUseProgram(program)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)

        uniform1("uT", t * current.speed + t * 0.2f)
        uniform1("uR", current.radius)
        uniform1("uSw", current.swirl)
        uniform1("uN", current.noise)
        uniform1("uSc", current.scatter)
        uniform1("uLvl", level)
        uniform1("uLo", 0.5f + levelSmooth * 0.8f)
        uniform1("uHi", (levelSmooth * 1.3f - 0.2f).coerceAtLeast(0f))
        uniform1("uPs", current.pointSize)
        uniform1("uAsp", width.toFloat() / height.toFloat())
        uniform1("uDpr", height / 1080f)
        uniform1("uB", breathe)
        uniform1("uFlat", current.flat)
        uniform1("uFlick", current.flicker)
        uniform1("uMaxPs", maxPointSize)
        uniform1("uCm", current.colorMix)
        val driftX = sin(t * 0.017f) * 0.02f
        val driftY = cos(t * 0.013f) * 0.015f
        GLES30.glUniform2f(location("uDrift"), driftX, driftY)
        GLES30.glUniform3f(location("uC"), accentR, accentG, accentB)
        GLES30.glUniform3f(location("uBase"), 0.22f, 0.62f, 1.0f)
        GLES30.glUniform3f(location("uTint"), current.tintR, current.tintG, current.tintB)

        uniform1("uBloom", 0f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
        uniform1("uBloom", 1f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount / 2)
    }

    private fun uniform1(name: String, value: Float) {
        GLES30.glUniform1f(location(name), value)
    }

    private fun location(name: String): Int = uniform[name] ?: -1

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSrc) ?: return 0
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc) ?: return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glBindAttribLocation(program, 0, "a")
        GLES30.glLinkProgram(program)
        val link = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, link, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (link[0] == 0) {
            Log.e(TAG, "link: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private companion object {
        const val TAG = "OrbRenderer"
    }
}

private fun OrbTarget.lerpToward(target: OrbTarget, k: Float) {
    brightness += (target.brightness - brightness) * k
    radius += (target.radius - radius) * k
    swirl += (target.swirl - swirl) * k
    speed += (target.speed - speed) * k
    noise += (target.noise - noise) * k
    scatter += (target.scatter - scatter) * k
    agitation += (target.agitation - agitation) * k
    colorMix += (target.colorMix - colorMix) * k
    pointSize += (target.pointSize - pointSize) * k
    flicker += (target.flicker - flicker) * k
    tintR += (target.tintR - tintR) * k
    tintG += (target.tintG - tintG) * k
    tintB += (target.tintB - tintB) * k
    flat += (target.flat - flat) * k
}
