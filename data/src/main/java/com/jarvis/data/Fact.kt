package com.jarvis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "facts")
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val source: String,
    val createdAt: Long,
    val pinned: Boolean = false,
)
