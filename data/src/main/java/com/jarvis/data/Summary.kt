package com.jarvis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One rolling summary for every channel. [turnsSinceCloud] counts pruned turns since the last cloud rewrite. */
@Entity(tableName = "summary")
data class Summary(
    @PrimaryKey val id: Int = ID,
    val text: String = "",
    val updatedAt: Long = 0,
    val turnsSinceCloud: Int = 0,
) {
    companion object {
        const val ID = 1
    }
}
