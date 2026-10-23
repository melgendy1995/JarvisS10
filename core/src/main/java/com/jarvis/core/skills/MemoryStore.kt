package com.jarvis.core.skills

data class StoredFact(
    val key: String,
    val value: String,
)

/** On-device facts. The phone implements this with Room. Cloud brains never see the store itself. */
interface MemoryStore {
    suspend fun remember(key: String, value: String, source: String)
    suspend fun recall(query: String): List<StoredFact>
    suspend fun forget(query: String): Int
}
