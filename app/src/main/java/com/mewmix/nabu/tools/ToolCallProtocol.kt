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
        lines += "The name field must exactly match one of the available tool names below."
        lines += "Never invent tool names, never use the user's label/title as the tool name, and never output comments or pseudo-code."
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

        lines += "Examples:"
        if (tools.any { it.isAvailable && it.name == "set_timer" }) {
            lines += """- If the user says "set a timer for 13 seconds called tea", respond with exactly: <tool_call>{"name":"set_timer","arguments":{"seconds":13,"message":"tea"}}</tool_call>"""
        }
        if (tools.any { it.isAvailable && it.name == "set_alarm" }) {
            lines += """- If the user says "set an alarm for 7:30 called wake up", respond with exactly: <tool_call>{"name":"set_alarm","arguments":{"hour":7,"minute":30,"message":"wake up"}}</tool_call>"""
        }
        if (tools.any { it.isAvailable && it.name == "get_weather" }) {
            lines += """- If the user asks for weather in Seattle, respond with exactly: <tool_call>{"name":"get_weather","arguments":{"location":"Seattle"}}</tool_call>"""
        }
        if (tools.any { it.isAvailable && it.name == "search_web_context" }) {
            lines += """- If the user asks you to search the web for eclipse news, respond with exactly: <tool_call>{"name":"search_web_context","arguments":{"query":"eclipse news"}}</tool_call>"""
        }

        lines += "Tool results arrive in a user message starting with TOOL_RESULT as JSON."
        lines += "After receiving TOOL_RESULT, produce a normal user-facing answer."
        lines += "Path rules for tool arguments:"
        lines += "- Always use absolute Android paths."
        lines += "- If the user says Downloads/downloads, use /sdcard/Download."
        lines += "- If the user provided an explicit path, use that exact path."
        lines += "- Prefer shared-storage roots: /sdcard/Download, /sdcard/Documents, /sdcard/Pictures, /sdcard/Music."
        lines += "- Do not guess private/system paths like /data, /system, or /proc for tool calls."
        lines += "- If path is unclear, ask for clarification instead of guessing."

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

        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseToolCallJson(trimmed)?.let { return it }
        }

        val inlineJson = extractJsonObject(trimmed)
        if (inlineJson != trimmed && inlineJson.startsWith("{") && inlineJson.endsWith("}")) {
            parseToolCallJson(inlineJson)?.let { return it }
        }

        return parseCommandStyleToolCall(text)
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
        parseToolCallJsonStrict(jsonText)?.let { return it }

        val normalized = normalizeLikelyToolCallJson(jsonText)
        if (normalized != jsonText) {
            parseToolCallJsonStrict(normalized)?.let { return it }
        }

        return null
    }

    private fun parseToolCallJsonStrict(jsonText: String): ToolCall? {
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

    /**
     * Some local models emit almost-JSON tool payloads, for example:
     * {name":"list_files",arguments={"path":"/sdcard"}}
     * Normalize those into valid JSON before a second parse attempt.
     */
    private fun normalizeLikelyToolCallJson(rawText: String): String {
        val candidate = extractJsonObject(rawText)
        if (candidate.isBlank()) return rawText

        return candidate
            .replace(
                Regex("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\"\\s*:"),
                "$1\"$2\":"
            )
            .replace(
                Regex("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*="),
                "$1\"$2\":"
            )
            .replace(
                Regex("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:"),
                "$1\"$2\":"
            )
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            text.substring(start, end + 1)
        } else {
            text.trim()
        }
    }

    private fun parseCommandStyleToolCall(text: String): ToolCall? {
        val compact = text.trim()
        if (compact.isBlank()) return null

        val listFilesRegex =
            Regex("(?is)^\\s*list_files\\b(?:\\s|\\(|:)+(?:path\\s*[=:]\\s*)?[\"']?([^\\s\"'\\),`]+)")
        listFilesRegex.find(compact)?.groupValues?.getOrNull(1)?.let { rawPath ->
            val path = rawPath.trim()
            if (path.startsWith("/")) {
                return ToolCall("list_files", mapOf("path" to path))
            }
        }

        val readFileRegex =
            Regex("(?is)^\\s*read_file\\b(?:\\s|\\(|:)+(?:path\\s*[=:]\\s*)?[\"']?([^\\s\"'\\),`]+)")
        readFileRegex.find(compact)?.groupValues?.getOrNull(1)?.let { rawPath ->
            val path = rawPath.trim()
            if (path.startsWith("/")) {
                return ToolCall("read_file", mapOf("path" to path))
            }
        }

        val alarmRegex =
            Regex(
                "(?is)^\\s*(?:set_alarm|set\\s+alarm|set\\s+an\\s+alarm|alarm)\\b.*?hour\\s*[=:]\\s*(\\d{1,2})\\b.*?minute\\s*[=:]\\s*(\\d{1,2})\\b(?:.*?message\\s*[=:]\\s*[\"']?([^\"']+?)[\"']?)?\\s*$"
            )
        alarmRegex.find(compact)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()
            val minute = match.groupValues[2].toIntOrNull()
            val message = match.groupValues.getOrNull(3)?.trim().orEmpty()
            if (hour != null && minute != null) {
                return ToolCall(
                    "set_alarm",
                    buildMap {
                        put("hour", hour)
                        put("minute", minute)
                        if (message.isNotBlank()) {
                            put("message", message)
                        }
                    }
                )
            }
        }

        return null
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
