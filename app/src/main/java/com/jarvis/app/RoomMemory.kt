package com.jarvis.app

import com.jarvis.core.skills.MemoryStore
import com.jarvis.core.skills.StoredFact
import com.jarvis.data.MemoryRepository

class RoomMemory(
    private val repository: MemoryRepository,
) : MemoryStore {
    override suspend fun remember(key: String, value: String, source: String) {
        repository.remember(key, value, source)
    }

    override suspend fun recall(query: String): List<StoredFact> {
        return repository.recall(query).map { StoredFact(it.key, it.value) }
    }

    override suspend fun forget(query: String): Int = repository.forget(query)
}
