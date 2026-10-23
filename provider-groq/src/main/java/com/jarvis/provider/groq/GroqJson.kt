package com.jarvis.provider.groq

import com.jarvis.core.skills.SkillRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal fun SkillRegistry.groqTools(): List<JsonObject> {
    return registered().mapNotNull { skill ->
        skill.groqTool().toJsonElement() as? JsonObject
    }
}

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this.toDouble())
    is Map<*, *> -> buildJsonObject {
        for ((key, value) in this@toJsonElement) {
            if (key != null) put(key.toString(), value.toJsonElement())
        }
    }
    is Iterable<*> -> buildJsonArray {
        for (item in this@toJsonElement) add(item.toJsonElement())
    }
    else -> JsonPrimitive(toString())
}
