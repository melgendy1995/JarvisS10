package com.jarvis.app.system

/**
 * Pure ICE liveness decisions for the WebRTC publisher, driven by an injectable clock.
 * Maps ICE events to drop-now / drop-later / ignore per peer, gives a DISCONNECTED peer
 * a reconnect grace window, and makes dropping idempotent: the drop of each peer can be
 * claimed at most once, and repeat FAILED/CLOSED events do not re-request the drop.
 * Never touches WebRTC objects itself.
 */
internal class PeerLiveness<K : Any>(
    private val nowMillis: () -> Long,
    val graceMillis: Long,
) {
    enum class IceState { CONNECTED, DISCONNECTED, FAILED, CLOSED, OTHER }

    enum class Verdict { DROP_NOW, DROP_LATER, IGNORE }

    private class Entry {
        /** Set by claimDrop: the one permitted close/dispose has been taken. */
        var dropped = false
        /** A FAILED/CLOSED drop is already queued, so later such events are ignored. */
        var pending = false
        var failing = false
        var deadlineMillis = Long.MAX_VALUE
    }

    private val peers = HashMap<K, Entry>()

    fun onIceState(id: K, state: IceState): Verdict {
        return synchronized(this) {
            val entry = peers.getOrPut(id) { Entry() }
            if (entry.dropped || entry.pending) {
                Verdict.IGNORE
            } else when (state) {
                IceState.FAILED, IceState.CLOSED -> {
                    entry.pending = true
                    entry.failing = false
                    entry.deadlineMillis = Long.MAX_VALUE
                    Verdict.DROP_NOW
                }
                IceState.DISCONNECTED -> {
                    entry.failing = true
                    if (entry.deadlineMillis == Long.MAX_VALUE) entry.deadlineMillis = nowMillis() + graceMillis
                    Verdict.DROP_LATER
                }
                IceState.CONNECTED, IceState.OTHER -> {
                    entry.failing = false
                    entry.deadlineMillis = Long.MAX_VALUE
                    Verdict.IGNORE
                }
            }
        }
    }

    /** True only while the peer is still disconnected/failed with its grace window spent. */
    fun stillFailing(id: K, atMillis: Long = nowMillis()): Boolean {
        synchronized(this) {
            val entry = peers[id] ?: return false
            return !entry.dropped && !entry.pending && entry.failing && entry.deadlineMillis <= atMillis
        }
    }

    /** Consume the single allowed close/dispose per peer: exactly one caller ever gets true. */
    fun claimDrop(id: K): Boolean {
        synchronized(this) {
            val entry = peers.getOrPut(id) { Entry() }
            if (entry.dropped) return false
            entry.dropped = true
            entry.pending = false
            entry.failing = false
            entry.deadlineMillis = Long.MAX_VALUE
            return true
        }
    }
}
