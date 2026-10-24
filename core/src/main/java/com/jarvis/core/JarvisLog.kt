package com.jarvis.core

/** Device log that stays quiet in JVM unit tests, where android.util.Log is a stub. */
object JarvisLog {
    fun i(tag: String, message: String) {
        try {
            android.util.Log.i(tag, message)
        } catch (_: RuntimeException) {
        }
    }
}
