package com.mewmix.nabu.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.Tool
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolCallProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

object ActionPlanner {
    private const val MAX_PLAN_STEPS = 7
    private const val PLANNER_TIMEOUT_MS = 30_000L

    data class Plan(
        val toolCalls: List<ToolCall>,
        val response: String,
        val source: String
    )

    fun shouldUseActionPlanner(userMessage: String, selectedTools: List<Tool>): Boolean {
        val text = userMessage.lowercase(Locale.US)
        if (selectedTools.none { it.name != "list_tools" }) return false
        if (text.isBlank()) return false
        if (selectedTools.any { it.name == "read_screen" } &&
            "screen" in text &&
            listOf("read", "describe", "what", "tell me", "inspect").any(text::contains)
        ) {
            return false
        }
        val actionSignals = listOf(
            " then ",
            " after ",
            " in ",
            " for ",
            "turn ",
            "toggle ",
            "set ",
            "send ",
            "text ",
            "message ",
            "compose ",
            "draft ",
            "open ",
            "call ",
            "schedule ",
            "remind ",
            "file",
            "folder",
            "directory",
            "download",
            "document",
            "photo",
            "picture",
            "read ",
            "write ",
            "create ",
            "delete ",
            "remove ",
            "find ",
            "zip",
            "unzip",
            "archive",
            "compress",
            "extract"
        )
        return actionSignals.any { text.contains(it) }
    }

    suspend fun planWithModel(
        backend: LlmBackend,
        userMessage: String,
        selectedTools: List<Tool>,
        recentContext: List<LlmMessage> = emptyList(),
        timeoutMs: Long = PLANNER_TIMEOUT_MS
    ): Plan? {
        val tools = selectedTools
            .filter { it.isAvailable }
            .distinctBy { it.name }
            .take(14)
        if (!shouldUseActionPlanner(userMessage, tools)) return null

        val response = withTimeoutOrNull(timeoutMs) {
            val deferred = CompletableDeferred<String>()
            val builder = StringBuilder()
            backend.sendMessage(buildPlannerConversation(userMessage, tools, recentContext)) { partial, done ->
                if (!done) {
                    builder.append(partial)
                    return@sendMessage
                }
                if (partial.isNotEmpty()) builder.append(partial)
                deferred.complete(builder.toString())
            }
            deferred.await()
        } ?: return null

        return parsePlan(response, tools.map { it.name }.toSet())
    }

    fun parsePlan(rawResponse: String, availableToolNames: Set<String>): Plan? {
        val root = extractFirstJsonObject(rawResponse)
            ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            ?: return null

        val type = root.optString("type").lowercase(Locale.US)
        if (type != "action_plan") return null

        val confirmationRequired = root.optBoolean("requires_confirmation")
        val response = root.optString("response")
            .ifBlank { root.optString("speak") }
            .ifBlank { "I prepared the requested actions." }
        if (confirmationRequired) {
            return Plan(
                toolCalls = emptyList(),
                response = response.ifBlank { "I need confirmation before running that action plan." },
                source = "model_confirmation_required"
            )
        }

        val steps = root.getAsJsonArray("steps") ?: return null
        if (steps.size() !in 1..MAX_PLAN_STEPS) return null

        val toolCalls = steps.mapNotNull { element ->
            val step = element.asJsonObjectOrNull() ?: return@mapNotNull null
            parseStep(step, availableToolNames)
        }
        if (toolCalls.size != steps.size()) return null
        return Plan(toolCalls = toolCalls, response = response, source = "model_action_plan")
    }

    private fun parseStep(step: JsonObject, availableToolNames: Set<String>): ToolCall? {
        val toolName = step.optString("tool_name")
            .ifBlank { step.optString("tool") }
            .ifBlank { step.optString("name") }
        if (toolName.isBlank()) return null
        val delaySeconds = step.optLong("delay_seconds")
        val runAtLocal = step.optString("run_at_local")
        val recurrence = step.optString("recurrence").ifBlank { "none" }
        val arguments = step.get("tool_arguments")?.jsonObjectToMap()
            ?: step.get("arguments")?.jsonObjectToMap()
            ?: emptyMap()

        val isDeferred = delaySeconds != null || runAtLocal.isNotBlank()
        return if (isDeferred) {
            if ("schedule_action" !in availableToolNames || toolName == "schedule_action") return null
            val scheduleArgs = linkedMapOf<String, Any>(
                "title" to step.optString("title").ifBlank { "Run $toolName" },
                "instruction" to step.optString("instruction")
                    .ifBlank { "Run $toolName${delaySeconds?.let { " after $it seconds" } ?: ""}." },
                "tool_name" to toolName,
                "tool_arguments" to arguments,
                "recurrence" to recurrence
            )
            delaySeconds?.let { scheduleArgs["delay_seconds"] = it }
            if (runAtLocal.isNotBlank()) scheduleArgs["run_at_local"] = runAtLocal
            ToolCall("schedule_action", scheduleArgs)
        } else {
            if (toolName !in availableToolNames) return null
            ToolCall(toolName, arguments)
        }
    }

    private fun buildPlannerConversation(
        userMessage: String,
        tools: List<Tool>,
        recentContext: List<LlmMessage>
    ): List<LlmMessage> {
        val toolList = tools.joinToString("\n") { tool ->
            val params = if (tool.parameters.isEmpty()) "{}" else tool.parameters.entries.joinToString(
                prefix = "{",
                postfix = "}"
            ) { (key, description) -> "\"$key\":\"${description.take(120)}\"" }
            "- ${tool.name}: ${tool.description.take(180)} params=$params"
        }
        val context = recentContext
            .takeLast(4)
            .joinToString("\n") { "${it.role}: ${it.content.take(240)}" }
            .ifBlank { "(none)" }
        val filesystemContext = ToolCallProtocol.buildFilesystemPathContext(tools)
            ?.let { "\n\n$it" }
            .orEmpty()
        val system = """
            You are Nabu's action planner. Convert the user's request into a validated JSON action_plan.
            Do not answer conversationally outside JSON.
            Use only tools from this list_tools response:
            $toolList$filesystemContext

            Contract:
            {
              "type":"action_plan",
              "requires_confirmation":false,
              "response":"brief user-facing summary or conversational content",
              "steps":[
                {"tool_name":"tool_name","tool_arguments":{}},
                {"delay_seconds":10,"tool_name":"tool_name","tool_arguments":{},"title":"short title"}
              ]
            }
            Rules:
            - Return 1 to $MAX_PLAN_STEPS steps.
            - For delayed actions, put delay_seconds or run_at_local on the step. Do not call schedule_action directly.
            - Delays are relative to now. If the user says one action happens after a previous delayed action, use the cumulative delay.
            - Put jokes, spoken acknowledgements, or non-tool wording in response, not in steps.
            - If the request is ambiguous or dangerous, set requires_confirmation true and return no steps.
            - If a requested file operation has no exact matching tool in the list, set requires_confirmation true and explain the missing tool in response.
            - Use exact tool names and exact parameter names.
        """.trimIndent()
        val user = """
            Recent context:
            $context

            User request:
            $userMessage
        """.trimIndent()
        return listOf(
            LlmMessage(role = "system", content = system),
            LlmMessage(role = "user", content = user)
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaping = false
        for (index in start until text.length) {
            val ch = text[index]
            if (escaping) {
                escaping = false
                continue
            }
            if (ch == '\\' && inString) {
                escaping = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonElement.jsonObjectToMap(): Map<String, Any> {
        if (!isJsonObject) return emptyMap()
        return asJsonObject.entrySet().mapNotNull { (key, value) ->
            value.toKotlinValue()?.let { key to it }
        }.toMap()
    }

    private fun JsonElement.toKotlinValue(): Any? {
        return when {
            isJsonNull -> null
            isJsonObject -> jsonObjectToMap()
            isJsonArray -> asJsonArray.mapNotNull { it.toKotlinValue() }
            isJsonPrimitive -> {
                val primitive = asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> {
                        val asLong = runCatching { primitive.asLong }.getOrNull()
                        val asDouble = runCatching { primitive.asDouble }.getOrNull()
                        if (asLong != null && asDouble != null && asDouble == asLong.toDouble()) {
                            asLong.takeIf { it > Int.MAX_VALUE || it < Int.MIN_VALUE } ?: asLong.toInt()
                        } else {
                            asDouble ?: primitive.asString
                        }
                    }
                    else -> primitive.asString
                }
            }
            else -> null
        }
    }

    private fun JsonObject.optString(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()

    private fun JsonObject.optBoolean(name: String): Boolean =
        get(name)?.takeIf { !it.isJsonNull }?.let {
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean -> it.asBoolean
                it.isJsonPrimitive -> it.asString.equals("true", ignoreCase = true)
                else -> false
            }
        } ?: false

    private fun JsonObject.optLong(name: String): Long? =
        get(name)?.takeIf { !it.isJsonNull }?.let {
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber -> it.asLong
                it.isJsonPrimitive -> it.asString.trim().toLongOrNull()
                else -> null
            }
        }

    private fun JsonObject.getAsJsonArray(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}
