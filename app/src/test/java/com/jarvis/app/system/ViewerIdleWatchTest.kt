package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viewer idle-timeout logic for the on-demand camera, driven by a fake clock. */
class ViewerIdleWatchTest {
    private var now = 0L
    private fun clock() = now
    private fun watch() = ViewerIdleWatch(idleTimeoutMillis = 45_000, nowMillis = ::clock)

    @Test
    fun anOpenStreamViewerHoldsTheCamera() {
        val watch = watch()
        watch.arrived("stream-1")
        now = 30_000
        assertNull(watch.idleMillis())
        assertEquals(1, watch.activeCount())
    }

    @Test
    fun idleTimesOutFortyFiveSecondsAfterTheLastViewerLeaves() {
        val watch = watch()
        watch.arrived("stream-1")
        watch.departed("stream-1")
        now = 44_999
        assertEquals(44_999L, watch.idleMillis())
        assertFalse(watch.idleTimedOut())
        now = 45_000
        assertTrue(watch.idleTimedOut())
    }

    @Test
    fun aSnapshotHitExpiredCountsIdleFromItsExpiry() {
        val watch = watch()
        now = 10_000
        watch.touched("snapshot")
        now = 54_999
        assertEquals(1, watch.activeCount())
        assertFalse(watch.idleTimedOut())
        now = 55_000
        assertEquals(0, watch.activeCount())
        // The hit happened at 10 s; it stayed "fresh" until 55 s.
        assertEquals(0L, watch.idleMillis())
        now = 99_999
        assertFalse(watch.idleTimedOut())
        now = 100_000
        assertTrue(watch.idleTimedOut())
    }

    @Test
    fun anotherSnapshotHitPushesTheIdleWindowOut() {
        val watch = watch()
        watch.touched("snapshot")
        now = 40_000
        watch.touched("snapshot") // fresh until 85 s, then the 45 s idle window runs.
        now = 84_999
        assertFalse(watch.idleTimedOut())
        now = 85_000
        assertEquals(0L, watch.idleMillis())
        now = 129_999
        assertFalse(watch.idleTimedOut())
        now = 130_000
        assertTrue(watch.idleTimedOut())
    }

    @Test
    fun aNewViewerResetsTheIdleWindow() {
        val watch = watch()
        watch.arrived("stream-1")
        watch.departed("stream-1")
        now = 44_000
        assertFalse(watch.idleTimedOut())
        watch.arrived("stream-2")
        now = 88_000
        assertNull(watch.idleMillis())
        assertFalse(watch.idleTimedOut())
    }

    @Test
    fun webRtcAndSnapshotViewersMixIntoOneIdleWindow() {
        val watch = watch()
        watch.arrived("webrtc")
        now = 20_000
        watch.touched("snapshot")
        now = 30_000
        watch.departed("webrtc")
        // Last viewer activity: the snapshot at 20 s, fresh until 65 s.
        now = 64_999
        assertEquals(1, watch.activeCount())
        assertNull(watch.idleMillis())
        now = 65_000
        assertEquals(0L, watch.idleMillis())
        now = 110_000
        assertTrue(watch.idleTimedOut())
    }
}

class ViewerIdleWatchGraceTest {
    private var now = 0L
    private fun clock() = now
    private fun watch() = ViewerIdleWatch(idleTimeoutMillis = 45_000, nowMillis = ::clock, departGraceMillis = 15_000)

    @Test
    fun theIdleCountdownWaitsOutTheDepartGrace() {
        val watch = watch()
        watch.arrived("webrtc")
        watch.departed("webrtc")
        // A transient ICE DISCONNECTED drop must not read as an empty shelf.
        now = 14_999
        assertNull(watch.idleMillis())
        assertFalse(watch.idleTimedOut())
        // The grace bakes into the idle clock, so idle reads 15 s at its end.
        now = 15_000
        assertEquals(15_000L, watch.idleMillis())
    }

    @Test
    fun theIdleTimeoutUnchangedByTheGrace() {
        val watch = watch()
        watch.arrived("webrtc")
        watch.departed("webrtc")
        // Same 45-s cutoff as before; the grace only delays the "empty" moment.
        now = 44_999
        assertFalse(watch.idleTimedOut())
        now = 45_000
        assertTrue(watch.idleTimedOut())
    }

    @Test
    fun aNewViewerInsideTheGraceCancelsIt() {
        val watch = watch()
        watch.arrived("webrtc")
        watch.departed("webrtc")
        now = 5_000
        watch.arrived("webrtc-2")
        now = 19_000
        assertNull(watch.idleMillis())
        assertEquals(1, watch.activeCount())
    }

    @Test
    fun zeroGraceKeepsTheOldBehavior() {
        val watch = ViewerIdleWatch(idleTimeoutMillis = 45_000, nowMillis = ::clock)
        watch.arrived("webrtc")
        watch.departed("webrtc")
        now = 45_000
        assertTrue(watch.idleTimedOut())
    }

    @Test
    fun noGraceWhenOtherViewersRemain() {
        val watch = watch()
        watch.arrived("webrtc")
        watch.touched("snapshot")
        watch.departed("webrtc")
        now = 1_000
        assertEquals(1, watch.activeCount())
        assertNull(watch.idleMillis())
    }
}
