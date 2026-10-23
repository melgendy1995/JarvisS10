package com.jarvis.provider.gemini

import java.time.Instant

fun interface TimeSource {
    fun now(): Instant
}

object SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
}
