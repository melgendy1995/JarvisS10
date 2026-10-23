package com.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Profile::class, Fact::class, Summary::class, Turn::class],
    version = 1,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        fun create(context: Context): JarvisDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                "jarvis-memory.db",
            ).fallbackToDestructiveMigration(dropAllTables = true).build()
        }
    }
}
