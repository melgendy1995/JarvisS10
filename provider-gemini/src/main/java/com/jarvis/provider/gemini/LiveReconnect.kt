package com.jarvis.provider.gemini

/** One reconnect for a GoAway, an unexpected close, or a first-byte timeout. An intentional close does not reconnect. */
object LiveReconnect {
    fun allow(alreadyReconnected: Boolean, intentionalClose: Boolean): Boolean {
        return !alreadyReconnected && !intentionalClose
    }
}
