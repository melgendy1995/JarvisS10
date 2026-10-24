package com.jarvis.core.skills

/**
 * Phone-side launcher, playback, and volume. The skill parses speech;
 * the app module performs the Android calls.
 */
interface AppControl {
    suspend fun perform(call: SkillCall): SkillResult
}
