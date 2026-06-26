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
    private val wrappedToolCallRegex =
        Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>", setOf(RegexOption.IGNORE_CASE))
    private val colonWrappedToolCallRegex =
        Regex("<tool_call:\\s*([\\s\\S]*?)\\s*</tool_call>", setOf(RegexOption.IGNORE_CASE))
    private val malformedColonToolCallRegex =
        Regex("<tool_call:\\s*([\\s\\S]*?)(?:<tool_call\\|>|$)", setOf(RegexOption.IGNORE_CASE))
    private val fencedJsonRegex =
        Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", setOf(RegexOption.IGNORE_CASE))

    fun buildSystemPrompt(basePrompt: String, tools: List<Tool>, externalStoragePath: String? = null): String {
        if (tools.isEmpty()) {
            val lines = mutableListOf<String>()
            if (basePrompt.isNotBlank()) {
                lines += basePrompt.trim()
                lines += ""
            }
            lines += "You may call tools to help the user."
            lines += "Tool-call format: ⦗{\"name\":\"tool_name\",\"arguments\":{}}⦘"
            lines += "Do not add extra text around a tool call."
            lines += "Never return an empty response."
            lines += "To discover available tools, call: ⦗{\"name\":\"list_tools\",\"arguments\":{}}⦘"
            return lines.joinToString("\n").trim()
        }

        val lines = mutableListOf<String>()
        if (basePrompt.isNotBlank()) {
            lines += basePrompt.trim()
            lines += ""
        }

        lines += "You may call a tool when needed."
        lines += "Tool-call format only: <tool_call>{\"name\":\"tool_name\",\"arguments\":{}}</tool_call>"
        lines += "Do not add extra text around a tool call."
        lines += "Never return an empty response."
        lines += "If no tool is needed, answer with at least one short plain-text sentence."
        lines += "After TOOL_RESULT, reply to the user with a normal sentence."
        lines += "Do not invent or upgrade tool results. If a tool says it opened a composer, dialer, app, settings panel, camera, browser, or share sheet, say only that it opened; do not say the action was completed."
        lines += "The name must exactly match one of these tools:"

        tools
            .filter { it.isAvailable }
            .sortedBy { it.name }
            .forEach { tool ->
                val parameterSpec = tool.parameters.keys.sorted().joinToString(", ").ifBlank { "none" }
                lines += "- ${tool.name}($parameterSpec): ${tool.description}"
            }

        if (tools.any { it.isAvailable && it.name == "set_timer" }) {
            lines += """Example timer: <tool_call>{"name":"set_timer","arguments":{"seconds":13,"message":"tea"}}</tool_call>"""
        }
        if (tools.any { it.isAvailable && it.name == "set_alarm" }) {
            lines += """Example alarm: <tool_call>{"name":"set_alarm","arguments":{"hour":7,"minute":30,"message":"wake up"}}</tool_call>"""
        }
        if (tools.any { it.isAvailable && it.name == "get_weather" }) {
            lines += """Example weather: <tool_call>{"name":"get_weather","arguments":{"location":"Seattle"}}</tool_call>"""
        }
        if (tools.any { it.isAvailable && it.name == "search_web_context" }) {
            lines += """Example search: <tool_call>{"name":"search_web_context","arguments":{"query":"eclipse news"}}</tool_call>"""
        }

        buildFilesystemPathContext(tools, externalStoragePath)?.let { lines += it }

        return lines.joinToString("\n")
    }

    fun buildFilesystemPathContext(tools: List<Tool>, externalStoragePath: String? = null): String? {
        val availableTools = tools.filter { it.isAvailable }
        val hasPathParam = availableTools.any { tool ->
            tool.parameters.keys.any { key -> key.contains("path", ignoreCase = true) }
        }
        if (!hasPathParam && availableTools.none { it.needsFilesystemPathHint() }) return null

        val rootPath = externalStoragePath ?: "/storage/emulated/0"
        return listOf(
            "Filesystem tool context:",
            "- File tools execute on the Android device through Glaive; paths refer to phone storage, not this Mac or repo.",
            "- Use absolute Android paths for any path or root_path argument. Prefer /sdcard/Download for downloads.",
            "- Shared storage root aliases: /sdcard and $rootPath.",
            "- Filesystem path structure: root=/sdcard; shared root=$rootPath.",
            "- Common dirs: /sdcard/Download, /sdcard/Documents, /sdcard/DCIM, /sdcard/Pictures, /sdcard/Movies, /sdcard/Music.",
            "- If the user gives a relative name, first call list_files or search_files under the likely common directory.",
            "- For search_files use root_path for the directory and query for the file/name fragment.",
            "- Do not attempt zip, unzip, copy, move, or rename unless an exact matching tool is listed.",
            "- After list_files, open/find children by appending the returned child name to that listed directory path."
        ).joinToString("\n")
    }

    private fun Tool.needsFilesystemPathHint(): Boolean {
        val normalizedName = name.lowercase()
        return normalizedName in setOf(
            "list_files",
            "read_file",
            "open_file",
            "find_file",
            "search_files",
            "write_file",
            "create_dir",
            "delete_file"
        ) || (
            parameters.keys.any { it.contains("path", ignoreCase = true) } &&
                normalizedName.contains("file")
            )
    }

    fun parseDirectUserToolCommand(text: String): ToolCall? {
        val match = Regex("""(?is)^\s*/tool\s+([a-zA-Z0-9_]+)(?:\s+(\{[\s\S]*\}))?\s*$""")
            .find(text.trim()) ?: return null
        val toolName = match.groupValues[1].trim()
        if (toolName.isBlank()) return null
        val argsJson = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (argsJson.isBlank()) {
            return ToolCall(toolName, emptyMap())
        }
        val argsObject = runCatching { JsonParser.parseString(argsJson).asJsonObject }.getOrNull()
            ?: return null
        return ToolCall(toolName, jsonToMap(argsObject))
    }

    fun extractToolCall(text: String): ToolCall? {
        val taggedJson = taggedToolCallRegex.find(text)?.groupValues?.getOrNull(1)
        if (!taggedJson.isNullOrBlank()) {
            return parseToolCallJson(taggedJson)
        }

        colonWrappedToolCallRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { body ->
                parseToolCallJson(body)?.let { return it }
                parseCommandStyleToolCall(body, allowGenericTool = true)?.let { return it }
            }

        malformedColonToolCallRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { body ->
                parseToolCallJson(body)?.let { return it }
                parseCommandStyleToolCall(body, allowGenericTool = true)?.let { return it }
            }

        wrappedToolCallRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { body ->
                parseToolCallJson(body)?.let { return it }
                parseCommandStyleToolCall(body, allowGenericTool = true)?.let { return it }
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

        return parseCommandStyleToolCall(text, allowGenericTool = false)
    }

    fun looksLikeToolControlText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        return trimmed.contains("<tool_call", ignoreCase = true) ||
            trimmed.startsWith("```") && extractToolCall(trimmed) != null
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

    private fun parseCommandStyleToolCall(text: String, allowGenericTool: Boolean): ToolCall? {
        val compact = text.trim()
        if (compact.isBlank()) return null
        val unwrapped = colonWrappedToolCallRegex.find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: malformedColonToolCallRegex.find(compact)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            ?: wrappedToolCallRegex.find(compact)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            ?: compact
            .replace(Regex("(?is)^\\s*<tool_call>\\s*"), "")
            .replace(Regex("(?is)^\\s*<tool_call:\\s*"), "")
            .replace(Regex("(?is)\\s*</tool_call>\\s*$"), "")
            .trim()

        val functionStyleRegex =
            Regex("(?is)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*(\\{[\\s\\S]*\\})\\s*\\)\\s*(?:[.。!！])?\\s*$")
        functionStyleRegex.find(unwrapped)?.let { match ->
            val toolName = match.groupValues[1].trim()
            val argsJson = match.groupValues[2].trim()
            val argsObject = runCatching { JsonParser.parseString(argsJson).asJsonObject }.getOrNull()
                ?: return@let
            return ToolCall(toolName, jsonToMap(argsObject))
        }

        if (allowGenericTool) {
            val emptyCallRegex =
                Regex("(?is)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:\\(\\s*\\)|\\{\\s*\\})\\s*(?:[.。!！])?\\s*$")
            emptyCallRegex.find(unwrapped)?.let { match ->
                return ToolCall(match.groupValues[1].trim(), emptyMap())
            }

            val commaArgumentsRegex =
                Regex("(?is)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*,\\s*(?:arguments|args)\\s*[:=]\\s*([\\s\\S]*?)\\s*(?:[.。!！])?\\s*$")
            commaArgumentsRegex.find(unwrapped)?.let { match ->
                val toolName = match.groupValues[1].trim()
                val arguments = parseLooseArgumentMap(match.groupValues[2].trim())
                if (arguments != null) return ToolCall(toolName, arguments)
            }

            val looseFunctionRegex =
                Regex("(?is)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*([\\s\\S]*?)\\s*\\)\\s*(?:[.。!！])?\\s*$")
            looseFunctionRegex.find(unwrapped)?.let { match ->
                val toolName = match.groupValues[1].trim()
                val arguments = parseLooseArgumentMap(match.groupValues[2].trim())
                if (arguments != null) return ToolCall(toolName, arguments)
            }

            val looseBraceRegex =
                Regex("(?is)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\{\\s*([\\s\\S]*?)\\s*\\}\\s*(?:[.。!！])?\\s*$")
            looseBraceRegex.find(unwrapped)?.let { match ->
                val toolName = match.groupValues[1].trim()
                val arguments = parseLooseArgumentMap(match.groupValues[2].trim())
                if (arguments != null) return ToolCall(toolName, arguments)
            }
        }

        val listFilesRegex =
            Regex("(?is)^\\s*list_files\\b(?:\\s|\\(|:)+(?:path\\s*[=:]\\s*)?[\"']?([^\\s\"'\\),`]+)")
        listFilesRegex.find(unwrapped)?.groupValues?.getOrNull(1)?.let { rawPath ->
            val path = rawPath.trim()
            if (path.startsWith("/")) {
                return ToolCall("list_files", mapOf("path" to path))
            }
        }

        val readFileRegex =
            Regex("(?is)^\\s*read_file\\b(?:\\s|\\(|:)+(?:path\\s*[=:]\\s*)?[\"']?([^\\s\"'\\),`]+)")
        readFileRegex.find(unwrapped)?.groupValues?.getOrNull(1)?.let { rawPath ->
            val path = rawPath.trim()
            if (path.startsWith("/")) {
                return ToolCall("read_file", mapOf("path" to path))
            }
        }

        val alarmRegex =
            Regex(
                "(?is)^\\s*(?:set_alarm|set\\s+alarm|set\\s+an\\s+alarm|alarm)\\b.*?hour\\s*[=:]\\s*(\\d{1,2})\\b.*?minute\\s*[=:]\\s*(\\d{1,2})\\b(?:.*?message\\s*[=:]\\s*[\"']?([^\"']+?)[\"']?)?\\s*$"
            )
        alarmRegex.find(unwrapped)?.let { match ->
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

    private fun parseLooseArgumentMap(rawText: String): Map<String, Any>? {
        val raw = rawText.trim().trimStart('{').trimEnd('}').trim()
        if (raw.isBlank()) return emptyMap()
        val arguments = linkedMapOf<String, Any>()
        val pairRegex =
            Regex("""(?is)([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*("[^"]*"|'[^']*'|true|false|-?\d+(?:\.\d+)?|[^,)}]+)""")
        for (match in pairRegex.findAll(raw)) {
            val key = match.groupValues[1].trim()
            val value = parseLooseScalar(match.groupValues[2].trim())
            arguments[key] = value
        }
        return arguments.takeIf { it.isNotEmpty() }
    }

    private fun parseLooseScalar(rawValue: String): Any {
        val value = rawValue.trim().trimEnd('.', '。', '!', '！')
        val unquoted = when {
            value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") -> value.substring(1, value.length - 1)
            value.length >= 2 && value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1)
            else -> value
        }
        return when {
            unquoted.equals("true", ignoreCase = true) -> true
            unquoted.equals("false", ignoreCase = true) -> false
            unquoted.toLongOrNull() != null -> unquoted.toLong()
            unquoted.toDoubleOrNull() != null -> unquoted.toDouble()
            else -> unquoted
        }
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
