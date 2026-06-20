package com.mewmix.nabu.actions

import android.content.Context
import com.mewmix.nabu.utils.DebugLogger

object ScheduledActionExecutor {
    private const val EARLY_TOLERANCE_MS = 1_000L

    data class Outcome(
        val action: ScheduledAction,
        val executionRun: ActionRun?
    )

    @Synchronized
    fun executeDueAction(
        context: Context,
        actionId: String,
        fallbackAction: ScheduledAction? = null
    ): Outcome? {
        val appContext = context.applicationContext
        val storedAction = ScheduledActionStore.find(appContext, actionId)
        if (storedAction?.completedAtEpochMs != null && storedAction.recurrence == ScheduledAction.RECURRENCE_NONE) {
            return null
        }
        val action = fallbackAction
            ?.takeIf { it.effectiveSteps().isNotEmpty() }
            ?: storedAction
            ?: fallbackAction
            ?: return null
        val now = System.currentTimeMillis()
        if (action.triggerAtEpochMs - now > EARLY_TOLERANCE_MS) {
            return null
        }

        DebugLogger.log(
            "ScheduledActionExecutor executing actionId=${action.id} title=${action.title} " +
                "steps=${action.effectiveSteps().size} fallback=${fallbackAction != null}"
        )
        val executionRun = if (action.effectiveSteps().isNotEmpty()) {
            ActionPlanRunner.run(appContext, action) { runContext, call ->
                val step = action.effectiveSteps().firstOrNull {
                    it.toolName == call.toolName && it.toolArguments == call.arguments
                }
                if (call.toolName == ActionTools.SCHEDULED_AGENT_STEP_TOOL && step != null) {
                    ScheduledAgentStepExecutor.execute(runContext, action, step)
                } else {
                    ActionTools.execute(runContext, call)
                }
            }
        } else {
            null
        }

        if (executionRun != null) {
            ScheduledActionStore.recordRun(appContext, action.id, executionRun)
        }

        val latestStoredAction = ScheduledActionStore.find(appContext, action.id) ?: action
        if (latestStoredAction.recurrence == ScheduledAction.RECURRENCE_DAILY ||
            latestStoredAction.recurrence == ScheduledAction.RECURRENCE_WEEKLY
        ) {
            val stepMs = if (latestStoredAction.recurrence == ScheduledAction.RECURRENCE_WEEKLY) {
                7L * 24L * 60L * 60L * 1000L
            } else {
                24L * 60L * 60L * 1000L
            }
            ScheduledActionScheduler.schedule(
                appContext,
                latestStoredAction.copy(
                    triggerAtEpochMs = latestStoredAction.triggerAtEpochMs + stepMs,
                    completedAtEpochMs = null
                )
            )
        } else {
            val finalRun = executionRun ?: ActionRun(
                id = java.util.UUID.randomUUID().toString(),
                actionId = action.id,
                startedAtEpochMs = now,
                finishedAtEpochMs = System.currentTimeMillis(),
                status = ActionRun.STATUS_SUCCEEDED,
                stepResults = emptyList(),
                summary = action.instruction
            )
            ScheduledActionStore.complete(appContext, action.id, finalRun)
            DebugLogger.log(
                "ScheduledActionExecutor completed actionId=${action.id} status=${finalRun.status} " +
                    "summary=${finalRun.summary}"
            )
        }

        return Outcome(action, executionRun)
    }
}
