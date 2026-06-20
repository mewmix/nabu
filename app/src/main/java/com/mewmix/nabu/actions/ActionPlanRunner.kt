package com.mewmix.nabu.actions

import android.content.Context
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import com.mewmix.nabu.utils.DebugLogger
import java.util.UUID

object ActionPlanRunner {
    private const val MAX_STEPS_PER_RUN = 8

    fun run(
        context: Context,
        action: ScheduledAction,
        executor: (Context, ToolCall) -> ToolResult? = { appContext, call ->
            ActionTools.execute(appContext, call)
        }
    ): ActionRun {
        val startedAt = System.currentTimeMillis()
        val allSteps = action.effectiveSteps()
        val steps = allSteps.take(MAX_STEPS_PER_RUN)
        val results = mutableListOf<ActionStepResult>()

        for (step in steps) {
            val stepStartedAt = System.currentTimeMillis()
            val result = if (ActionTools.isSchedulableTool(step.toolName)) {
                executor(context.applicationContext, ToolCall(step.toolName, step.toolArguments))
                    ?: ToolResult(
                        toolName = step.toolName,
                        output = "Scheduled tool '${step.toolName}' is unavailable.",
                        isError = true
                    )
            } else {
                ToolResult(
                    toolName = step.toolName,
                    output = "Tool '${step.toolName}' cannot run from scheduled background execution.",
                    isError = true
                )
            }
            val stepResult = ActionStepResult(
                stepId = step.id,
                title = step.title,
                toolName = step.toolName,
                startedAtEpochMs = stepStartedAt,
                finishedAtEpochMs = System.currentTimeMillis(),
                output = result.output,
                isError = result.isError
            )
            DebugLogger.log(
                "ActionPlanRunner step actionId=${action.id} step=${step.title} tool=${step.toolName} " +
                    "error=${result.isError} output=${result.output.take(160)}"
            )
            results += stepResult
            if (result.isError && !step.continueOnError) {
                break
            }
        }

        val finishedAt = System.currentTimeMillis()
        val failed = results.any { it.isError }
        val skippedCount = (allSteps.size - results.size).coerceAtLeast(0)
        val status = if (failed || skippedCount > 0) ActionRun.STATUS_FAILED else ActionRun.STATUS_SUCCEEDED
        return ActionRun(
            id = UUID.randomUUID().toString(),
            actionId = action.id,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            status = status,
            stepResults = results,
            summary = buildSummary(status, results, skippedCount)
        )
    }

    internal fun buildStep(
        toolName: String,
        toolArguments: Map<String, Any>,
        title: String? = null,
        continueOnError: Boolean = false
    ): ActionStep {
        return ActionStep(
            id = UUID.randomUUID().toString(),
            title = title?.trim().orEmpty().ifBlank { toolName },
            toolName = toolName,
            toolArguments = toolArguments,
            continueOnError = continueOnError
        )
    }

    private fun buildSummary(
        status: String,
        results: List<ActionStepResult>,
        skippedCount: Int
    ): String {
        if (results.isEmpty()) return "No scheduled steps were executed."
        val completed = results.count { !it.isError }
        val failed = results.count { it.isError }
        val suffix = if (skippedCount > 0) ", skipped $skippedCount" else ""
        return if (status == ActionRun.STATUS_SUCCEEDED) {
            "Completed $completed step${if (completed == 1) "" else "s"}$suffix."
        } else {
            "Completed $completed step${if (completed == 1) "" else "s"}, failed $failed$suffix."
        }
    }
}

fun ScheduledAction.effectiveSteps(): List<ActionStep> {
    val explicitSteps = (steps as? List<*>).orEmpty()
        .mapNotNull { it.toActionStepOrNull() }
        .filter { it.toolName.isNotBlank() }
    if (explicitSteps.isNotEmpty()) return explicitSteps
    val legacyTool = toolName?.trim().orEmpty()
    if (legacyTool.isBlank()) return emptyList()
    return listOf(
        ActionPlanRunner.buildStep(
            toolName = legacyTool,
            toolArguments = toolArguments.normalizedStringMap(),
            title = instruction.ifBlank { legacyTool }
        )
    )
}

private fun Any?.toActionStepOrNull(): ActionStep? {
    return when (this) {
        is ActionStep -> this.copy(toolArguments = toolArguments.normalizedStringMap())
        is Map<*, *> -> {
            val map = normalizedStringMap()
            val toolName = (map["toolName"] ?: map["tool_name"] ?: map["tool"])?.toString()?.trim().orEmpty()
            if (toolName.isBlank()) return null
            ActionStep(
                id = map["id"]?.toString()?.trim().orEmpty().ifBlank { UUID.randomUUID().toString() },
                title = map["title"]?.toString()?.trim().orEmpty().ifBlank { toolName },
                toolName = toolName,
                toolArguments = (map["toolArguments"] ?: map["tool_arguments"] ?: map["arguments"] ?: map["args"]).normalizedStringMap(),
                continueOnError = map["continueOnError"].toBooleanOrFalse() || map["continue_on_error"].toBooleanOrFalse()
            )
        }
        else -> null
    }
}

private fun Any?.normalizedStringMap(): Map<String, Any> {
    return when (this) {
        null -> emptyMap()
        is Map<*, *> -> entries.mapNotNull { (key, value) ->
            val stringKey = key?.toString() ?: return@mapNotNull null
            value?.let { stringKey to it }
        }.toMap()
        else -> emptyMap()
    }
}

private fun Any?.toBooleanOrFalse(): Boolean {
    return when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> trim().equals("true", ignoreCase = true) ||
            trim().equals("yes", ignoreCase = true) ||
            trim().equals("on", ignoreCase = true) ||
            trim() == "1"
        else -> false
    }
}
