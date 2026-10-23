package com.jarvis.app

import android.content.Context
import android.util.Log
import com.jarvis.data.MemoryRepository

/**
 * One startup pass. Clears every stored turn and the rolling summary.
 * Profile and facts stay. The flag is versioned so a later pass can use a new key.
 */
internal object PhoneFallbackMigration {
    private const val PREFS = "jarvis_memory_migration"
    private const val KEY = "cleared_phone_fallback_v1"

    suspend fun run(context: Context, memory: MemoryRepository) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        val cleared = memory.clearConversation()
        Log.i("JarvisMemory", "cleared turns=${cleared.turns} summary=${cleared.summary}")
        prefs.edit().putBoolean(KEY, true).commit()
    }
}
