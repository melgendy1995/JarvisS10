package com.jarvis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: Int = ID,
    val name: String,
    val language: String,
    val persona: String,
) {
    companion object {
        const val ID = 1
        const val DEFAULT_NAME = "Mahmoud"
        const val DEFAULT_LANGUAGE = "en"
    }
}
