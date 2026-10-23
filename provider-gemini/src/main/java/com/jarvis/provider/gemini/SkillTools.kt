package com.jarvis.provider.gemini

import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.SkillResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal data class ToolOutcome(
    val part: FunctionResponsePart,
    val spoken: String,
    val needsPermission: String?,
)

internal object SkillTools {
    fun declarations(registry: SkillRegistry): List<FunctionDeclaration> {
        return registry.registered().mapNotNull { skill ->
            val raw = skill.geminiDeclaration() as? Map<*, *> ?: return@mapNotNull null
            val name = raw["name"] as? String ?: skill.id
            val description = raw["description"] as? String ?: skill.id
            val parameters = raw["parameters"] as? Map<*, *>
            val properties = parameters?.get("properties") as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val required = (parameters?.get("required") as? List<*>).orEmpty().map { it.toString() }.toSet()
            val schemas = LinkedHashMap<String, Schema>()
            val optional = ArrayList<String>()
            for ((key, value) in properties) {
                val prop = key?.toString() ?: continue
                val spec = value as? Map<*, *> ?: continue
                schemas[prop] = schemaFor(prop, spec)
                if (prop !in required) optional += prop
            }
            FunctionDeclaration(name, description, schemas, optional)
        }
    }

    suspend fun respond(registry: SkillRegistry, utterance: String, call: FunctionCallPart): ToolOutcome {
        val skill = registry.registered().firstOrNull { it.id == call.name }
        val slots = LinkedHashMap<String, String>()
        for ((key, value) in call.args) {
            slots[key] = jsonSlot(value)
        }
        if ("millis" !in slots && "duration_ms" in slots) {
            slots["millis"] = slots.getValue("duration_ms")
        }
        val result = when {
            skill == null -> SkillResult("I don't have that tool.", success = false)
            else -> try {
                skill.run(SkillCall(skillId = skill.id, utterance = utterance, slots = slots))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                SkillResult("That didn't work.", success = false)
            }
        }
        val json = buildJsonObject {
            put("text", JsonPrimitive(result.text))
            put("success", JsonPrimitive(result.success))
        }
        val id = call.id
        val part = if (id.isNullOrBlank()) {
            FunctionResponsePart(call.name, json)
        } else {
            FunctionResponsePart(call.name, json, id)
        }
        return ToolOutcome(part, result.text, result.needsPermission)
    }

    private fun schemaFor(name: String, spec: Map<*, *>): Schema {
        val description = spec["description"] as? String ?: name
        val enumValues = (spec["enum"] as? List<*>)?.map { it.toString() }.orEmpty()
        return when (spec["type"]?.toString()) {
            "boolean" -> Schema.boolean(description)
            "integer" -> Schema.integer(description)
            "number" -> Schema.double(description)
            "string" -> if (enumValues.isEmpty()) Schema.string(description) else Schema.enumeration(enumValues, description)
            else -> if (enumValues.isEmpty()) Schema.string(description) else Schema.enumeration(enumValues, description)
        }
    }

    private fun jsonSlot(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        is JsonObject -> element.toString()
        else -> element.toString()
    }
}
