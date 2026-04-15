package com.mewmix.nabu.actions

import android.content.Context
import com.mewmix.nabu.tools.Tool
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ActionTools {
    val tools: List<Tool> = listOf(
        Tool(
            name = "schedule_action",
            description = "Schedule a reminder/action at a specific local date-time.",
            parameters = mapOf(
                "instruction" to "What should happen when due.",
                "run_at_local" to "Local datetime in yyyy-MM-dd HH:mm format.",
                "title" to "Short title.",
                "recurrence" to "none, daily, or weekly"
            )
        ),
        Tool(
            name = "schedule_fuzzy_action",
            description = "Interpret a fuzzy scheduling request and create a scheduled action when possible.",
            parameters = mapOf(
                "request" to "Natural-language request like: remind me when the sun goes down in Berlin"
            )
        ),
        Tool(
            name = "list_scheduled_actions",
            description = "List pending scheduled actions.",
            parameters = emptyMap()
        ),
        Tool(
            name = "search_web_context",
            description = "Search the web for context and return concise findings.",
            parameters = mapOf(
                "query" to "What to search for."
            )
        ),
        Tool(
            name = "get_current_time",
            description = "Get the current local date and time.",
            parameters = emptyMap()
        ),
        Tool(
            name = "get_weather",
            description = "Get the current weather for a specific location.",
            parameters = mapOf(
                "location" to "The location to get weather for."
            )
        ),
        Tool(
            name = "save_memory",
            description = "Save a user preference or fact to memory.",
            parameters = mapOf(
                "fact" to "The preference or fact to save."
            )
        ),
        Tool(
            name = "retrieve_memory",
            description = "Retrieve all saved user preferences and facts.",
            parameters = emptyMap()
        ),
        Tool(
            name = "set_alarm",
            description = "Set an audible alarm for a specific time.",
            parameters = mapOf(
                "hour" to "The hour (0-23) to set the alarm for.",
                "minute" to "The minute (0-59) to set the alarm for.",
                "message" to "A message or title for the alarm."
            )
        ),
        Tool(
            name = "set_timer",
            description = "Set an audible timer for a specific duration.",
            parameters = mapOf(
                "seconds" to "The duration of the timer in seconds.",
                "message" to "A message or title for the timer."
            )
        )
    )

    fun execute(context: Context, call: ToolCall): ToolResult? {
        return when (call.toolName) {
            "schedule_action" -> runScheduleAction(context, call)
            "schedule_fuzzy_action" -> runFuzzyAction(context, call)
            "list_scheduled_actions" -> runListActions(context)
            "search_web_context" -> runWebSearch(call)
            "get_current_time" -> runGetCurrentTime(call)
            "get_weather" -> runGetWeather(call)
            "save_memory" -> runSaveMemory(context, call)
            "retrieve_memory" -> runRetrieveMemory(context, call)
            "set_alarm" -> runSetAlarm(context, call)
            "set_timer" -> runSetTimer(context, call)
            else -> null
        }
    }

    private fun runGetCurrentTime(call: ToolCall): ToolResult {
        val current = LocalDateTime.now(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return ToolResult(call.toolName, "Current local time is: ${current.format(formatter)}")
    }

    private fun runGetWeather(call: ToolCall): ToolResult {
        val location = call.arguments["location"]?.toString()?.trim().orEmpty()
        if (location.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: location", true)
        }
        val weather = WeatherAction.getWeather(location)
        return ToolResult(call.toolName, weather.message, weather.isError)
    }

    private fun runSaveMemory(context: Context, call: ToolCall): ToolResult {
        val fact = call.arguments["fact"]?.toString()?.trim().orEmpty()
        if (fact.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: fact", true)
        }
        MemoryStore.saveMemory(context, fact)
        return ToolResult(call.toolName, "Saved memory: \$fact")
    }

    private fun runRetrieveMemory(context: Context, call: ToolCall): ToolResult {
        val memories = MemoryStore.retrieveMemories(context)
        if (memories.isEmpty()) {
            return ToolResult(call.toolName, "No memories saved.")
        }
        return ToolResult(call.toolName, memories.joinToString("\n") { "- \$it" })
    }

    private fun runSetAlarm(context: Context, call: ToolCall): ToolResult {
        val hourRaw = call.arguments["hour"]
        val minuteRaw = call.arguments["minute"]
        val message = call.arguments["message"]?.toString()?.trim().orEmpty()

        if (isMissingArgument(hourRaw) || isMissingArgument(minuteRaw)) {
            return ToolResult(call.toolName, "Missing required parameters: hour, minute", true)
        }

        val hour = parseWholeNumberArgument(hourRaw)
        val minute = parseWholeNumberArgument(minuteRaw)

        if (hour == null || minute == null) {
            return ToolResult(call.toolName, "Invalid hour or minute format.", true)
        }

        val result = AlarmTimerAction.setAlarm(context, hour, minute, message)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runSetTimer(context: Context, call: ToolCall): ToolResult {
        val secondsRaw = call.arguments["seconds"]
        val message = call.arguments["message"]?.toString()?.trim().orEmpty()

        if (isMissingArgument(secondsRaw)) {
            return ToolResult(call.toolName, "Missing required parameter: seconds", true)
        }

        val seconds = parseWholeNumberArgument(secondsRaw)
        if (seconds == null) {
            return ToolResult(call.toolName, "Invalid seconds format.", true)
        }

        val result = AlarmTimerAction.setTimer(context, seconds, message)
        return ToolResult(call.toolName, result.message, result.isError)
    }

    private fun runScheduleAction(context: Context, call: ToolCall): ToolResult {
        val instruction = call.arguments["instruction"]?.toString()?.trim().orEmpty()
        val runAtLocal = call.arguments["run_at_local"]?.toString()?.trim().orEmpty()
        val title = call.arguments["title"]?.toString()?.trim().ifNullOrBlank("Scheduled action")
        val recurrence = call.arguments["recurrence"]?.toString()?.trim()?.lowercase().normalizeRecurrence()

        if (instruction.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: instruction", true)
        }
        if (runAtLocal.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: run_at_local", true)
        }

        val triggerAt = parseLocalDateTime(runAtLocal)
            ?: return ToolResult(call.toolName, "Invalid run_at_local format. Use yyyy-MM-dd HH:mm", true)

        val action = ScheduledActionScheduler.createAndSchedule(
            context = context,
            title = title,
            instruction = instruction,
            triggerAtEpochMs = triggerAt,
            recurrence = recurrence
        )

        return ToolResult(
            call.toolName,
            "Scheduled '${action.title}' for ${formatEpoch(action.triggerAtEpochMs)} (recurrence=${action.recurrence}, id=${action.id})."
        )
    }

    private fun runFuzzyAction(context: Context, call: ToolCall): ToolResult {
        val request = call.arguments["request"]?.toString()?.trim().orEmpty()
        if (request.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: request", true)
        }
        val resolved = FuzzyActionInterpreter.resolveInstruction(request)
            ?: inferFromWeb(request)
            ?: return ToolResult(
                call.toolName,
                "I couldn't confidently parse that request yet. Try explicit schedule_action with run_at_local.",
                true
            )

        val action = ScheduledActionScheduler.createAndSchedule(
            context,
            resolved.title,
            resolved.instruction,
            resolved.triggerAtEpochMs,
            resolved.recurrence
        )

        return ToolResult(
            call.toolName,
            "Scheduled fuzzy action '${action.title}' for ${formatEpoch(action.triggerAtEpochMs)} (recurrence=${action.recurrence}, id=${action.id})."
        )
    }

    private fun runListActions(context: Context): ToolResult {
        val actions = ScheduledActionStore.list(context)
        if (actions.isEmpty()) return ToolResult("list_scheduled_actions", "No scheduled actions.")
        val lines = actions.joinToString("\n") {
            "- ${it.title} at ${formatEpoch(it.triggerAtEpochMs)} (recurrence=${it.recurrence}, id=${it.id})"
        }
        return ToolResult("list_scheduled_actions", lines)
    }

    private fun runWebSearch(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult(call.toolName, "Missing required parameter: query", true)
        }
        val result = WebActionReasoner.search(query)
        return ToolResult(call.toolName, WebActionReasoner.summarize(result), result.isError)
    }

    private fun inferFromWeb(request: String): FuzzyActionInterpreter.ResolvedAction? {
        val inferredTime = WebActionReasoner.inferTimeFromWeb(request) ?: return null
        return FuzzyActionInterpreter.ResolvedAction(
            title = "Web-inferred reminder",
            instruction = request.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            triggerAtEpochMs = inferredTime,
            recurrence = ScheduledAction.RECURRENCE_NONE
        )
    }

    private fun parseLocalDateTime(value: String): Long? {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return runCatching {
            LocalDateTime.parse(value, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun formatEpoch(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()

    private fun String?.ifNullOrBlank(default: String): String = if (this.isNullOrBlank()) default else this

    private fun String?.normalizeRecurrence(): String = when (this) {
        ScheduledAction.RECURRENCE_DAILY -> ScheduledAction.RECURRENCE_DAILY
        ScheduledAction.RECURRENCE_WEEKLY -> ScheduledAction.RECURRENCE_WEEKLY
        else -> ScheduledAction.RECURRENCE_NONE
    }

    private fun isMissingArgument(value: Any?): Boolean =
        value == null || (value is String && value.isBlank())

    private fun parseWholeNumberArgument(value: Any?): Int? {
        return when (value) {
            null -> null
            is Int -> value
            is Long -> value.toIntOrNullExact()
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
            is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toInt()
            is Number -> {
                val longValue = value.toLong()
                if (value.toDouble().isFinite() && value.toDouble() == longValue.toDouble()) {
                    longValue.toIntOrNullExact()
                } else {
                    null
                }
            }
            is String -> {
                val trimmed = value.trim()
                if (!trimmed.matches(Regex("^-?\\d+(?:\\.0+)?$"))) {
                    null
                } else {
                    trimmed.substringBefore('.').toIntOrNull()
                }
            }
            else -> null
        }
    }

    private fun Long.toIntOrNullExact(): Int? = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
}
