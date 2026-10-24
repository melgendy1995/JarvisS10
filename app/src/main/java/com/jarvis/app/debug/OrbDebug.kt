package com.jarvis.app.debug

import com.jarvis.orb.OrbState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Latched orb override for screenshots. Only the debug manifest receiver writes it.
 * adb shell am broadcast -a com.jarvis.app.DEBUG_STATE -p com.jarvis.app --es state idle
 */
object OrbDebug {
    val forced = MutableStateFlow<OrbState?>(null)

    /** @return false when [name] is not one of the five orb states. */
    fun force(name: String?): Boolean {
        val state = parseDebugOrb(name) ?: return false
        forced.value = state
        return true
    }
}

fun parseDebugOrb(name: String?): OrbState? = when (name?.trim()?.lowercase(Locale.US)) {
    "idle" -> OrbState.IDLE
    "listening" -> OrbState.LISTENING
    "thinking" -> OrbState.THINKING
    "speaking" -> OrbState.SPEAKING
    "error" -> OrbState.ERROR
    else -> null
}
