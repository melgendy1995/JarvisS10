package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ICE liveness decisions driving the WebRTC drop logic, timeline driven by a fake clock. */
class PeerLivenessTest {
    private var now = 0L

    private fun liveness(graceMs: Long = 10_000) =
        PeerLiveness<String>(nowMillis = { now }, graceMillis = graceMs)

    @Test
    fun closedAfterDropIsIgnored() {
        val live = liveness()
        assertTrue(live.claimDrop("peer-1"))
        assertFalse(live.claimDrop("peer-1"))
        assertFalse(live.stillFailing("peer-1"))
        assertEquals(
            PeerLiveness.Verdict.IGNORE,
            live.onIceState("peer-1", PeerLiveness.IceState.CLOSED),
        )
    }

    @Test
    fun repeatedClosedEventsCauseAtMostOneDrop() {
        val live = liveness()
        assertEquals(
            PeerLiveness.Verdict.DROP_NOW,
            live.onIceState("peer-1", PeerLiveness.IceState.CLOSED),
        )
        assertEquals(
            PeerLiveness.Verdict.IGNORE,
            live.onIceState("peer-1", PeerLiveness.IceState.CLOSED),
        )
        assertEquals(
            PeerLiveness.Verdict.IGNORE,
            live.onIceState("peer-1", PeerLiveness.IceState.CLOSED),
        )
    }

    @Test
    fun disconnectedThenConnectedWithinGraceDoesNotDrop() {
        val live = liveness()
        assertEquals(
            PeerLiveness.Verdict.DROP_LATER,
            live.onIceState("peer-1", PeerLiveness.IceState.DISCONNECTED),
        )
        now = 9_999
        assertEquals(
            PeerLiveness.Verdict.IGNORE,
            live.onIceState("peer-1", PeerLiveness.IceState.CONNECTED),
        )
        now = 60_000
        assertFalse(live.stillFailing("peer-1"))
    }

    @Test
    fun disconnectedStayingPastGraceDropsOnce() {
        val live = liveness()
        assertEquals(
            PeerLiveness.Verdict.DROP_LATER,
            live.onIceState("peer-1", PeerLiveness.IceState.DISCONNECTED),
        )
        now = 9_999
        assertFalse(live.stillFailing("peer-1"))
        now = 10_000
        assertTrue(live.stillFailing("peer-1"))
        assertTrue(live.claimDrop("peer-1"))
        assertFalse(live.claimDrop("peer-1"))
        assertFalse(live.stillFailing("peer-1"))
    }

    @Test
    fun failedDropsOnce() {
        val live = liveness()
        assertEquals(
            PeerLiveness.Verdict.DROP_NOW,
            live.onIceState("peer-1", PeerLiveness.IceState.FAILED),
        )
        assertTrue(live.claimDrop("peer-1"))
        assertFalse(live.claimDrop("peer-1"))
        assertEquals(
            PeerLiveness.Verdict.IGNORE,
            live.onIceState("peer-1", PeerLiveness.IceState.FAILED),
        )
    }

    @Test
    fun failedAfterDisconnectedDropsImmediatelyWithinGrace() {
        val live = liveness()
        now = 50_000
        assertEquals(
            PeerLiveness.Verdict.DROP_LATER,
            live.onIceState("peer-1", PeerLiveness.IceState.DISCONNECTED),
        )
        assertEquals(
            PeerLiveness.Verdict.DROP_NOW,
            live.onIceState("peer-1", PeerLiveness.IceState.FAILED),
        )
        assertTrue(live.claimDrop("peer-1"))
        assertFalse(live.claimDrop("peer-1"))
    }
}
