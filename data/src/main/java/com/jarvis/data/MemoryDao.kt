package com.jarvis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MemoryDao {
    @Query("SELECT * FROM profile WHERE id = :id LIMIT 1")
    suspend fun profile(id: Int): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: Profile)

    @Query("SELECT * FROM facts")
    suspend fun facts(): List<Fact>

    @Insert
    suspend fun insertFact(fact: Fact): Long

    @Update
    suspend fun updateFact(fact: Fact)

    @Query("DELETE FROM facts WHERE id IN (:ids)")
    suspend fun deleteFacts(ids: List<Long>)

    @Query("SELECT * FROM summary WHERE id = :id LIMIT 1")
    suspend fun summary(id: Int): Summary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: Summary)

    @Query("SELECT * FROM turns WHERE channel = :channel ORDER BY timestamp ASC, id ASC")
    suspend fun turns(channel: String): List<Turn>

    @Insert
    suspend fun insertTurn(turn: Turn): Long

    @Query("DELETE FROM turns WHERE id IN (:ids)")
    suspend fun deleteTurns(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM turns")
    suspend fun countTurns(): Int

    @Query("DELETE FROM turns")
    suspend fun deleteAllTurns()
}
