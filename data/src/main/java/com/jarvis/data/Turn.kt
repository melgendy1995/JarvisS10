package com.jarvis.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "turns",
    indices = [Index(value = ["channel", "timestamp"])],
)
data class Turn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val brainId: String,
    val channel: String,
    val timestamp: Long,
)

object TurnRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object Channels {
    const val VOICE = "voice"
    const val TELEGRAM = "telegram"
}
