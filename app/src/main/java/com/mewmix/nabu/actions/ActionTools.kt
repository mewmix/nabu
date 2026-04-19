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
        )
    )

    fun execute(context: Context, call: ToolCall): ToolResult? {
        return when (call.toolName) {
            "schedule_action" -> runScheduleAction(context, call)
            "schedule_fuzzy_action" -> runFuzzyAction(context, call)
            "list_scheduled_actions" -> runListActions(context)
            "search_web_context" -> runWebSearch(call)
            else -> null
        }
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
        val hits = WebActionReasoner.search(query)
        return ToolResult(call.toolName, WebActionReasoner.summarize(hits))
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
}
