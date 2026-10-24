package com.jarvis.core.camera

/**
 * PowerManager thermal status values are stable: MODERATE is 2, SEVERE is 3.
 * The service checks those constants against these numbers at startup.
 * MODERATE and above drop resolution and frame rate. SEVERE and above stop.
 */
enum class ThermalTier {
    FULL,
    REDUCED,
    STOP,
}

object CameraThermal {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3

    fun tier(status: Int): ThermalTier = when {
        status >= SEVERE -> ThermalTier.STOP
        status >= MODERATE -> ThermalTier.REDUCED
        else -> ThermalTier.FULL
    }
}

data class CameraProfile(
    val width: Int,
    val height: Int,
    val fpsMin: Int,
    val fpsMax: Int,
    val jpegQuality: Int,
)

object CameraProfiles {
    const val FULL_WIDTH = 1280
    const val FULL_HEIGHT = 720
    const val FULL_FPS_MIN = 15
    const val FULL_FPS_MAX = 30
    const val REDUCED_WIDTH = 640
    const val REDUCED_HEIGHT = 480
    const val REDUCED_FPS_MIN = 10
    const val REDUCED_FPS_MAX = 15

    val FULL = CameraProfile(FULL_WIDTH, FULL_HEIGHT, FULL_FPS_MIN, FULL_FPS_MAX, 70)
    val REDUCED = CameraProfile(REDUCED_WIDTH, REDUCED_HEIGHT, REDUCED_FPS_MIN, REDUCED_FPS_MAX, 55)

    fun forTier(tier: ThermalTier): CameraProfile? = when (tier) {
        ThermalTier.FULL -> FULL
        ThermalTier.REDUCED -> REDUCED
        ThermalTier.STOP -> null
    }
}
