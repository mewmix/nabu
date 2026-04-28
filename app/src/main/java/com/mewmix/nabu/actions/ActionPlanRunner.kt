package com.mewmix.nabu.actions

import android.content.Context
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
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
    val explicitSteps = steps.orEmpty().filter { it.toolName.isNotBlank() }
    if (explicitSteps.isNotEmpty()) return explicitSteps
    val legacyTool = toolName?.trim().orEmpty()
    if (legacyTool.isBlank()) return emptyList()
    return listOf(
        ActionPlanRunner.buildStep(
            toolName = legacyTool,
            toolArguments = toolArguments,
            title = instruction.ifBlank { legacyTool }
        )
    )
}
