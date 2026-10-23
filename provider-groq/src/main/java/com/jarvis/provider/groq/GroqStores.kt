package com.jarvis.provider.groq

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/** Rotation index and per-model token spends. No secrets. */
class SharedPrefsGroqStore(context: Context) : GroqStateStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): GroqSnapshot {
        return decodeSnapshot(prefs.getString(KEY, null))
    }

    override fun write(snapshot: GroqSnapshot) {
        prefs.edit().putString(KEY, encodeSnapshot(snapshot)).commit()
    }

    private companion object {
        const val PREFS = "jarvis_groq_rotation"
        const val KEY = "snapshot"
    }
}

/**
 * Worker bearer in Android Keystore-backed prefs. The value is entered once in settings.
 * A keystore failure leaves Groq unconfigured rather than falling back to plaintext.
 */
class KeystoreBearerStore(context: Context) {
    private val prefs: SharedPreferences? = open(context.applicationContext)

    fun get(): String {
        val stored = prefs ?: return ""
        return try {
            normalizeBearer(stored.getString(KEY, "").orEmpty())
        } catch (error: Throwable) {
            Log.w(TAG, "bearer read failed", error)
            ""
        }
    }

    fun isOpen(): Boolean = prefs != null

    fun set(token: String): Boolean {
        val editor = prefs?.edit() ?: return false
        editor.putString(KEY, normalizeBearer(token))
        return editor.commit()
    }

    private fun open(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "keystore unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "JarvisGroq"
        const val FILE = "jarvis_groq_bearer"
        const val KEY = "bearer"
    }
}

/** Trims a pasted worker bearer. A leading "Bearer " is the header, not part of the secret. */
internal fun normalizeBearer(raw: String): String {
    var token = raw.trim().removePrefix("\uFEFF").trim()
    val prefix = "bearer "
    if (token.length >= prefix.length && token.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
        token = token.substring(prefix.length).trim()
    }
    return token.replace("\r", "").replace("\n", "")
}

internal fun encodeSnapshot(snapshot: GroqSnapshot): String {
    return buildJsonObject {
        put("next", JsonPrimitive(snapshot.nextIndex))
        put("cooldowns", buildJsonObject {
            snapshot.cooldownsUntilEpochMillis.forEach { (model, until) ->
                put(model, JsonPrimitive(until))
            }
        })
        put("spends", buildJsonObject {
            snapshot.spends.forEach { (model, events) ->
                put(model, buildJsonArray {
                    events.forEach { spend ->
                        add(buildJsonObject {
                            put("at", JsonPrimitive(spend.atEpochMillis))
                            put("tokens", JsonPrimitive(spend.tokens))
                        })
                    }
                })
            }
        })
    }.toString()
}

internal fun decodeSnapshot(raw: String?): GroqSnapshot {
    if (raw.isNullOrBlank()) return GroqSnapshot()
    val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return GroqSnapshot()
    val next = (root["next"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    val cooldowns = LinkedHashMap<String, Long>()
    (root["cooldowns"] as? JsonObject)?.forEach { (model, value) ->
        val until = (value as? JsonPrimitive)?.longOrNull ?: return@forEach
        cooldowns[model] = until
    }
    val spends = LinkedHashMap<String, List<TokenSpend>>()
    (root["spends"] as? JsonObject)?.forEach { (model, value) ->
        val events = (value as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val tokens = (obj["tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            TokenSpend(at, tokens)
        }
        if (events.isNotEmpty()) spends[model] = events
    }
    return GroqSnapshot(next, cooldowns, spends)
}
