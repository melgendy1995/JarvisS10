package com.jarvis.app.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shelf clock for Asia/Riyadh. Night is 23:00 inclusive through 06:00 exclusive.
 * Digits stay Western so the date reads dd/mm/yyyy from across the room.
 */
object ShelfClock {
    val ZONE: ZoneId = ZoneId.of("Asia/Riyadh")

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)

    fun inRiyadh(time: ZonedDateTime): ZonedDateTime = time.withZoneSameInstant(ZONE)

    fun formatDate(time: ZonedDateTime): String = dateFormat.format(inRiyadh(time))

    fun formatTime(time: ZonedDateTime): String = timeFormat.format(inRiyadh(time))

    fun isNight(time: ZonedDateTime): Boolean {
        val hour = inRiyadh(time).hour
        return hour >= 23 || hour < 6
    }

    /** First strong character decides the caption, matching [androidx.compose.ui.text.style.TextDirection.Content]. */
    fun prefersRtl(text: String): Boolean {
        for (element in text) {
            when (Character.getDirectionality(element)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                -> return false
                else -> Unit
            }
        }
        return false
    }
}
