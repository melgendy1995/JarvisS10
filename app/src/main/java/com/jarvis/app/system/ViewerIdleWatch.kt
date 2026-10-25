package com.jarvis.app.system

/**
 * Viewer activity for the on-demand camera. Pure logic with an injectable clock
 * so unit tests can drive time. Sticky viewers (/stream, WebRTC) count until
 * they explicitly leave; snapshot viewers count while their last hit is fresh.
 */
internal class ViewerIdleWatch(
    private val idleTimeoutMillis: Long,
    private val nowMillis: () -> Long,
) {
    private data class Entry(val sticky: Boolean, var lastHitMillis: Long)

    private val viewers = HashMap<String, Entry>()
    private var lastBusyMillis: Long = nowMillis()

    /** Named viewer such as an open /stream connection or a WebRTC peer. */
    fun arrived(id: String) {
        synchronized(this) {
            viewers[id] = Entry(sticky = true, lastHitMillis = nowMillis())
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
            if (viewers.isEmpty()) lastBusyMillis = now
        }
    }

    fun activeCount(atMillis: Long = nowMillis()): Int {
        synchronized(this) {
            purgeExpired(atMillis)
            return viewers.size
        }
    }

    /** Milliseconds with no viewers, or null while viewers remain. */
    fun idleMillis(atMillis: Long = nowMillis()): Long? {
        synchronized(this) {
            purgeExpired(atMillis)
            if (viewers.isNotEmpty()) return null
            return atMillis - lastBusyMillis
        }
    }

    fun idleTimedOut(atMillis: Long = nowMillis()): Boolean {
        return (idleMillis(atMillis) ?: return false) >= idleTimeoutMillis
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
