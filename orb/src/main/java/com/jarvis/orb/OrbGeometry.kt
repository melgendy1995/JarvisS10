package com.jarvis.orb

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal const val ORB_POINT_COUNT = 3000

/**
 * Same distribution as the prototype's `build()`: a dense shell, inner haze, and a few outliers.
 * [seed] keeps the cloud stable across frames and process restarts.
 */
internal fun buildPointCloud(count: Int = ORB_POINT_COUNT, seed: Long = 42L): FloatArray {
    val rng = Random(seed)
    val data = FloatArray(count * 4)
    for (i in 0 until count) {
        val u = rng.nextFloat() * 2f - 1f
        val theta = rng.nextFloat() * (Math.PI * 2.0).toFloat()
        val horizontal = sqrt((1f - u * u).coerceAtLeast(0f))
        val k = rng.nextFloat()
        var radius = when {
            k < 0.62f -> 0.95f + (rng.nextFloat() - 0.5f) * 0.12f
            k < 0.92f -> Math.cbrt(rng.nextDouble()).toFloat() * 0.85f
            else -> 1.1f + rng.nextFloat() * 0.5f
        }
        val dx = horizontal * cos(theta)
        val dy = u
        val dz = horizontal * sin(theta)
        radius *= 1f +
            0.16f * sin(dx * 3.1f + dy * 2.3f) * sin(dz * 2.7f - dy * 1.9f) +
            0.06f * sin(dx * 9f + dz * 7f)
        val offset = i * 4
        data[offset] = dx * radius
        data[offset + 1] = dy * radius
        data[offset + 2] = dz * radius
        data[offset + 3] = rng.nextFloat()
    }
    return data
}
