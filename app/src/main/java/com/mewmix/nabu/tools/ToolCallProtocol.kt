package com.mewmix.nabu.tools

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ToolCallProtocol {
    private val gson = Gson()

    private val taggedToolCallRegex =
        Regex("<tool_call>\\s*(\\{[\\s\\S]*?\\})\\s*</tool_call>", setOf(RegexOption.IGNORE_CASE))
    private val fencedJsonRegex =
        Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", setOf(RegexOption.IGNORE_CASE))

    fun buildSystemPrompt(basePrompt: String, tools: List<Tool>): String {
        if (tools.isEmpty()) {
            return basePrompt.trim()
        }

        val lines = mutableListOf<String>()
        if (basePrompt.isNotBlank()) {
            lines += basePrompt.trim()
            lines += ""
        }

        lines += "You can call external tools when needed."
        lines += "When invoking a tool, respond with only this exact wrapper format:"
        lines += "<tool_call>{\"name\":\"tool_name\",\"arguments\":{}}</tool_call>"
        lines += "Do not include any extra text in a tool call response."
        lines += "Available tools:"

        tools
            .filter { it.isAvailable }
            .sortedBy { it.name }
            .forEach { tool ->
                val parameterSpec = if (tool.parameters.isEmpty()) {
                    "none"
                } else {
                    tool.parameters.entries.joinToString(", ") { (name, description) -> "$name=$description" }
                }
                lines += "- ${tool.name}: ${tool.description} (params: $parameterSpec)"
            }

        lines += "Tool results arrive in a user message starting with TOOL_RESULT as JSON."
        lines += "After receiving TOOL_RESULT, produce a normal user-facing answer."

        return lines.joinToString("\n")
    }

    fun extractToolCall(text: String): ToolCall? {
        val taggedJson = taggedToolCallRegex.find(text)?.groupValues?.getOrNull(1)
        if (!taggedJson.isNullOrBlank()) {
            return parseToolCallJson(taggedJson)
        }

        val fencedJson = fencedJsonRegex.find(text)?.groupValues?.getOrNull(1)
        if (!fencedJson.isNullOrBlank()) {
            return parseToolCallJson(fencedJson)
        }

        return null
    }

    fun formatToolResultForModel(result: ToolResult): String {
        val payload = mapOf(
            "tool" to result.toolName,
            "is_error" to result.isError,
            "output" to result.output
        )
        return "TOOL_RESULT ${gson.toJson(payload)}"
    }

    private fun parseToolCallJson(jsonText: String): ToolCall? {
        return runCatching {
            val json = JsonParser.parseString(jsonText).asJsonObject
            val toolName = sequenceOf(
                json.optString("name"),
                json.optString("tool"),
                json.optString("toolName")
            ).firstOrNull { it.isNotBlank() } ?: return null

            val rawArguments = json.opt("arguments") ?: json.opt("args") ?: return null
            val argumentsValue = when (rawArguments) {
                is JsonObject -> rawArguments
                is String -> runCatching { JsonParser.parseString(rawArguments).asJsonObject }.getOrNull()
                else -> null
            } ?: return null
            val arguments = jsonToMap(argumentsValue)

            ToolCall(toolName = toolName, arguments = arguments)
        }.getOrNull()
    }

    private fun jsonToMap(json: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for ((key, value) in json.entrySet()) {
            if (value == null || value.isJsonNull) {
                continue
            }
            map[key] = normalizeJsonValue(value)
        }
        return map
    }

    private fun normalizeJsonValue(value: JsonElement): Any {
        return when (value) {
            is JsonObject -> jsonToMap(value)
            is JsonArray -> buildList {
                for (item in value) {
                    if (item != null && !item.isJsonNull) {
                        add(normalizeJsonValue(item))
                    }
                }
            }
            else -> when {
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber
                value.isJsonPrimitive -> value.asString
                else -> value.toString()
            }
        }
    }

    private fun JsonObject.opt(field: String): Any? {
        val element = this.get(field) ?: return null
        if (element.isJsonNull) return null
        return when {
            element.isJsonObject -> element.asJsonObject
            element.isJsonArray -> element.asJsonArray
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asNumber
            else -> element.asString
        }
    }

    private fun JsonObject.optString(field: String): String {
        val element = this.get(field) ?: return ""
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            ""
        }
    }
}
