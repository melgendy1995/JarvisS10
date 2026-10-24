package com.jarvis.orb

import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Layered particle rings on a black shelf.
 *
 * Compose Canvas, not GLSurfaceView: gfxinfo records this draw thread, and the orb is one
 * center square. AGSL needs API 33. Rings are drawCircle primitives. A cubic ribbon is
 * software-rasterized into a mask every frame on S10 Skia, which missed 60 fps.
 *
 * Glow is PorterDuff ADD ([BlendMode.Plus] on the paint, drawn through the canvas, not
 * [DrawScope.drawCircle]'s blendMode argument). One [CompositingStrategy.Offscreen] layer
 * on [OrbView] holds the additive overlaps. A saveLayer per sprite does not. Gradients,
 * paints, the pose, and the matrix are allocated once. The frame clock invalidates draw
 * only, so the clock and captions are not recomposed.
 *
 * Rings, not a plasma blob: from 3–4 m a ring is one shape, and listening is that ring
 * expanding. Color crossfades between five cached gradients; motion eases in
 * [RingParams.approach].
 */
fun Modifier.ringOrb(state: OrbState, night: Boolean, demoMotion: Boolean): Modifier =
    this.then(RingOrbElement(state, night, demoMotion))

private class RingOrbElement(
    val state: OrbState,
    val night: Boolean,
    val demoMotion: Boolean,
) : ModifierNodeElement<RingOrbNode>() {
    override fun create(): RingOrbNode = RingOrbNode(state, night, demoMotion)

    override fun update(node: RingOrbNode) {
        node.state = state
        node.night = night
        node.demoMotion = demoMotion
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RingOrbElement) return false
        return state == other.state && night == other.night && demoMotion == other.demoMotion
    }

    override fun hashCode(): Int {
        var hash = state.hashCode()
        hash = 31 * hash + night.hashCode()
        hash = 31 * hash + demoMotion.hashCode()
        return hash
    }
}

private class RingOrbNode(
    @Volatile var state: OrbState,
    @Volatile var night: Boolean,
    @Volatile var demoMotion: Boolean,
) : Modifier.Node(), DrawModifierNode {
    private val current = RingParams.from(OrbState.IDLE.look())
    private val pose = Pose()
    private val matrix = android.graphics.Matrix()
    private val stroke = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        blendMode = BlendMode.Plus
    }
    private val fill = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Fill
        blendMode = BlendMode.Plus
    }
    private val bloom = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Fill
        blendMode = BlendMode.Plus
    }
    private var cores: Array<SoftRadial>? = null
    private var hots: Array<SoftRadial>? = null
    private var startNs = 0L
    private var lastNs = 0L
    private var seconds = 0f
    private var level = 0f
    private var mix = 1f
    private var shown = OrbState.IDLE
    private var previous = OrbState.IDLE

    override fun onAttach() {
        coroutineScope.launch {
            while (isActive) {
                withFrameNanos {
                    this@RingOrbNode.invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        step()
        if (size.minDimension < 2f) return
        val extent = size.minDimension * 0.5f
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val nightDim = if (night) NIGHT_GAIN else 1f
        val strobe = if (current.flash > 0.45f) flashStrobe(seconds) else 1f
        val alpha = (current.gain * nightDim * strobe).coerceIn(0f, 1.5f)
        val pose = updatePose(extent, cx, cy)
        val coreAlpha = (alpha * mix.coerceAtMost(1f)).coerceIn(0f, 1f)
        val halo = pose.extent * 0.62f * pose.scale
        stamp(cores()[shown.ordinal], pose.cx, pose.cy, halo, coreAlpha * 0.85f)
        if (mix < 0.995f) {
            stamp(
                cores()[previous.ordinal],
                pose.cx,
                pose.cy,
                halo,
                (alpha * (1f - mix)).coerceIn(0f, 1f),
            )
        }
        stamp(hots()[shown.ordinal], pose.cx, pose.cy, pose.extent * 0.16f * pose.scale, coreAlpha)
        rings(pose, alpha)
    }

    private fun step() {
        val now = System.nanoTime()
        if (startNs == 0L) {
            startNs = now
            lastNs = now
        }
        val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNs = now
        seconds = (now - startNs) / 1_000_000_000f
        val next = state
        if (next != shown) {
            previous = shown
            shown = next
            mix = 0f
        }
        val k = (1f - exp(-dt * 6f)).coerceIn(0f, 1f)
        mix += (1f - mix) * k
        if (mix > 0.995f) mix = 1f
        current.approach(shown.look(), k)
        val desired = if (demoMotion) {
            previewLevel(shown, seconds)
        } else if (shown == OrbState.LISTENING || shown == OrbState.SPEAKING) {
            OrbSignals.level.coerceIn(0f, 1f)
        } else {
            0f
        }
        level += (desired - level) * (1f - exp(-dt * 12f))
    }

    private fun updatePose(extent: Float, cx: Float, cy: Float): Pose {
        val breathe = 1f + current.breatheAmt * sin(seconds * current.breatheHz * TAU)
        val pulse = 1f + current.pulse * (level - 0.28f)
        pose.extent = extent
        pose.cx = cx
        pose.cy = cy
        pose.scale = (breathe * pulse).coerceIn(0.72f, 1.45f)
        pose.gate = current.expand.coerceIn(0f, 1f)
        pose.rate = (0.10f + 0.55f * pose.gate) * (0.35f + 0.95f * level)
        return pose
    }

    private fun DrawScope.rings(pose: Pose, alphaMul: Float) {
        val canvas = drawContext.canvas
        val gate = pose.gate
        val sprite = pose.extent * current.width * (0.34f + 0.16f * level)
        val outerWidth = pose.extent * 0.062f
        val glowWidth = pose.extent * 0.026f
        val hotWidth = pose.extent * 0.008f
        val center = Offset(pose.cx, pose.cy)
        val step = TAU / BEADS
        for (i in 0 until RINGS) {
            val travel = fract(seconds * pose.rate + i * 0.173f)
            val parked = 0.30f + i * 0.14f
            val moving = 0.10f + travel * 0.82f
            val radiusN = lerp(parked, moving, gate) * pose.scale
            val radius = (radiusN * pose.extent).coerceAtMost(pose.extent * 0.92f)
            val life = if (gate > 0.45f) {
                smoothstep(0f, 0.12f, travel) * (1f - smoothstep(0.72f, 1f, travel))
            } else {
                1f
            }
            if (life <= 0.02f || radius <= 1f) continue
            val ink = (0.9f * alphaMul * life).coerceIn(0f, 1f)
            stroke.strokeWidth = outerWidth
            stroke.color = Color(current.coreR, current.coreG, current.coreB, ink * 0.22f)
            canvas.drawCircle(center, radius, stroke)
            stroke.strokeWidth = glowWidth
            stroke.color = Color(current.hotR, current.hotG, current.hotB, ink * 0.45f)
            canvas.drawCircle(center, radius, stroke)
            stroke.strokeWidth = hotWidth
            stroke.color = Color(current.hotR, current.hotG, current.hotB, ink)
            canvas.drawCircle(center, radius, stroke)
            if (sprite <= 0.5f) continue
            fill.color = Color(current.hotR, current.hotG, current.hotB, ink * 0.7f)
            val base = seconds * current.spin + i * 0.62f +
                current.vortex * sin(radiusN * 6.2f - seconds * 2.1f)
            for (b in 0 until BEADS) {
                val ang = base + b * step
                val wobble = 1f + current.vortex * 0.13f * sin(ang * 2f - seconds * 3.2f)
                val beadR = radius * wobble
                canvas.drawCircle(
                    Offset(pose.cx + cos(ang) * beadR, pose.cy + sin(ang) * beadR),
                    sprite,
                    fill,
                )
            }
        }
    }

    private fun DrawScope.stamp(brush: SoftRadial, x: Float, y: Float, radius: Float, alpha: Float) {
        if (radius <= 0.5f || alpha <= 0.01f) return
        matrix.setScale(radius / SHADER_R, radius / SHADER_R)
        matrix.postTranslate(x, y)
        val shader = brush.shader()
        shader.setLocalMatrix(matrix)
        bloom.shader = shader
        bloom.alpha = alpha
        drawContext.canvas.drawCircle(Offset(x, y), radius, bloom)
    }

    private fun cores(): Array<SoftRadial> {
        val cached = cores
        if (cached != null) return cached
        val built = Array(OrbState.entries.size) { index ->
            val look = OrbState.entries[index].look()
            SoftRadial(look.coreR, look.coreG, look.coreB, wide = true)
        }
        cores = built
        return built
    }

    private fun hots(): Array<SoftRadial> {
        val cached = hots
        if (cached != null) return cached
        val built = Array(OrbState.entries.size) { index ->
            val look = OrbState.entries[index].look()
            SoftRadial(look.hotR, look.hotG, look.hotB, wide = false)
        }
        hots = built
        return built
    }
}

private class Pose {
    var extent = 0f
    var cx = 0f
    var cy = 0f
    var scale = 1f
    var gate = 0f
    var rate = 0f
}

/** White-hot center, zero alpha at the rim. One native shader, moved with [shader]'s matrix. */
private class SoftRadial(
    red: Float,
    green: Float,
    blue: Float,
    wide: Boolean,
) : ShaderBrush() {
    private val radial = RadialGradient(
        0f,
        0f,
        SHADER_R,
        intArrayOf(
            argb(if (wide) 210 else 245, red, green, blue),
            argb(if (wide) 90 else 120, red, green, blue),
            argb(if (wide) 24 else 36, red, green, blue),
            argb(0, red, green, blue),
        ),
        floatArrayOf(0f, if (wide) 0.28f else 0.18f, if (wide) 0.62f else 0.48f, 1f),
        Shader.TileMode.CLAMP,
    )

    fun shader(): Shader = radial

    override fun createShader(size: androidx.compose.ui.geometry.Size): Shader = radial
}

private fun argb(alpha: Int, red: Float, green: Float, blue: Float): Int {
    return android.graphics.Color.argb(
        alpha.coerceIn(0, 255),
        (red.coerceIn(0f, 1f) * 255f).toInt(),
        (green.coerceIn(0f, 1f) * 255f).toInt(),
        (blue.coerceIn(0f, 1f) * 255f).toInt(),
    )
}

private fun flashStrobe(seconds: Float): Float {
    val wave = 0.5f + 0.5f * sin(seconds * 8f)
    val sharp = wave * wave
    return 0.22f + 0.78f * sharp
}

private fun fract(value: Float): Float = value - floor(value)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val RINGS = 5
private const val BEADS = 12
private const val SHADER_R = 160f
private const val TAU = (PI * 2).toFloat()
private const val NIGHT_GAIN = 0.48f
