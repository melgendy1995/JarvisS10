package com.jarvis.app.system

/**
 * Viewer activity for the on-demand camera. Pure logic with an injectable clock
 * so unit tests can drive time. Sticky viewers (/stream, WebRTC) count until
 * they explicitly leave; snapshot viewers count while their last hit is fresh.
 * [departGraceMillis] holds "a viewer is still around" right after the last
 * sticky viewer leaves, so a WebRTC peer dropped after a transient ICE
 * DISCONNECTED does not immediately read as an empty shelf — the camera idle
 * countdown then starts once the grace is spent.
 */
internal class ViewerIdleWatch(
    private val idleTimeoutMillis: Long,
    private val nowMillis: () -> Long,
    private val departGraceMillis: Long = 0L,
) {
    private data class Entry(val sticky: Boolean, var lastHitMillis: Long)

    private val viewers = HashMap<String, Entry>()
    private var lastBusyMillis: Long = nowMillis()
    private var graceHoldUntilMillis: Long = 0L

    /** Named viewer such as an open /stream connection or a WebRTC peer. */
    fun arrived(id: String) {
        synchronized(this) {
            viewers[id] = Entry(sticky = true, lastHitMillis = nowMillis())
            graceHoldUntilMillis = 0L
            lastBusyMillis = nowMillis()
        }
    }

    /** Snapshot-style hit: keeps the viewer only for the idle timeout window. */
    fun touched(id: String) {
        synchronized(this) {
            viewers[id] = Entry(sticky = false, lastHitMillis = nowMillis())
            lastBusyMillis = nowMillis()
        }
    }

    fun departed(id: String) {
        val now = nowMillis()
        synchronized(this) {
            viewers.remove(id)
            if (viewers.isEmpty()) {
                lastBusyMillis = now
                graceHoldUntilMillis = now + departGraceMillis
            }
        }
    }

    fun activeCount(atMillis: Long = nowMillis()): Int {
        synchronized(this) {
            purgeExpired(atMillis)
            return viewers.size
        }
    }

    /** Milliseconds with no viewers, or null while a viewer (or the grace window) holds it. */
    fun idleMillis(atMillis: Long = nowMillis()): Long? {
        synchronized(this) {
            purgeExpired(atMillis)
            if (viewers.isNotEmpty()) return null
            if (atMillis < graceHoldUntilMillis) return null
            return atMillis - lastBusyMillis
        }
    }

    fun idleTimedOut(atMillis: Long = nowMillis()): Boolean {
        return (idleMillis(atMillis) ?: return false) >= idleTimeoutMillis
    }

    /**
     * True once the shelf is empty because the last sticky viewer (WebRTC peer
     * or /stream connection) left and the depart grace window is spent with no
     * other viewer present. The camera auto-stop uses this to stop on the
     * grace alone instead of stacking the full idle timeout on top of it.
     */
    fun departGraceExpired(atMillis: Long = nowMillis()): Boolean {
        synchronized(this) {
            purgeExpired(atMillis)
            if (viewers.isNotEmpty()) return false
            return graceHoldUntilMillis != 0L && atMillis >= graceHoldUntilMillis
        }
    }

    private fun purgeExpired(atMillis: Long) {
        val stale = viewers.filterValues { !it.sticky && it.lastHitMillis + idleTimeoutMillis <= atMillis }
        if (stale.isEmpty()) return
        for ((_, entry) in stale) {
            lastBusyMillis = maxOf(lastBusyMillis, entry.lastHitMillis + idleTimeoutMillis)
        }
        viewers.keys.removeAll(stale.keys)
    }
}
