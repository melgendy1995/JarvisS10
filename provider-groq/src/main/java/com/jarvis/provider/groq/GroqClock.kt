package com.jarvis.provider.groq

import java.time.Instant

fun interface GroqClock {
    fun now(): Instant
}

object SystemGroqClock : GroqClock {
    override fun now(): Instant = Instant.now()
}
