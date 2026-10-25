package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stop decisions for the auto-started camera: grace-only after the depart, idle-only otherwise. */
class CameraStopPolicyTest {
    private val idle = 45_000L

    private fun stop(
        running: Boolean = true,
        autoStarted: Boolean = true,
        starting: Boolean = false,
        graceExpired: Boolean = false,
        idleMillis: Long? = null,
    ): String? {
        return CameraStopPolicy.stopReason(
            running = running,
            autoStarted = autoStarted,
            starting = starting,
            departGraceExpired = graceExpired,
            idleMillis = idleMillis,
            idleTimeoutMillis = idle,
        )
    }

    @Test
    fun aCameraTheUserStartedNeverAutoStops() {
        // Even an empty shelf after the grace leaves a user-started camera alone.
        assertNull(stop(autoStarted = false, graceExpired = true, idleMillis = 20_000))
        assertNull(stop(autoStarted = false, idleMillis = 10L * idle))
    }

    @Test
    fun nothingRunsWhenTheCameraIsOffOrStarting() {
        assertNull(stop(running = false, graceExpired = true, idleMillis = 20_000))
        assertNull(stop(starting = true, graceExpired = true, idleMillis = 20_000))
    }

    @Test
    fun theShelfWaitsOutTheDepartGraceBeforeStopping() {
        // Grace active: idleMillis is null while the window ticks.
        assertNull(stop(graceExpired = false, idleMillis = null))
    }

    @Test
    fun theLastWebRtcDepartureStopsAfterTheGraceOnly() {
        // 15 s after the peer left: well under the 45 s idle timeout, still stops.
        val reason = stop(graceExpired = true, idleMillis = 15_000)
        assertEquals(CameraStopPolicy.REASON_NO_VIEWERS, reason)
    }

    @Test
    fun aSnapshotOnlyAudienceStillWaitsForTheIdleTimeout() {
        // No sticky departure happened, so only the long idle window applies.
        assertNull(stop(graceExpired = false, idleMillis = 44_999))
        assertEquals(CameraStopPolicy.REASON_IDLE, stop(graceExpired = false, idleMillis = 45_000))
    }

    @Test
    fun graceBeatsIdleOncePassed() {
        // Nobody re-joined for ages: still the no-viewers reason (logged once, not twice).
        assertEquals(CameraStopPolicy.REASON_NO_VIEWERS, stop(graceExpired = true, idleMillis = 5L * idle))
    }
}
