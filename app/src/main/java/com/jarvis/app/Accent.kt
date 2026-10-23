package com.jarvis.app

import com.jarvis.core.brain.BrainAccent as CoreAccent
import com.jarvis.orb.BrainAccent as OrbAccent

fun CoreAccent.toOrb(): OrbAccent = when (this) {
    CoreAccent.PHONE -> OrbAccent.PHONE
    CoreAccent.GEMINI -> OrbAccent.GEMINI
    CoreAccent.GROQ -> OrbAccent.GROQ
    CoreAccent.OFFLINE -> OrbAccent.OFFLINE
}
