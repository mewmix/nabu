package com.mewmix.nabu.actions

data class ScheduledAction(
    val id: String,
    val title: String,
    val instruction: String,
    val triggerAtEpochMs: Long,
    val recurrence: String = RECURRENCE_NONE,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),
    val steps: List<ActionStep>? = null,
    val lastRun: ActionRun? = null,
    val runHistory: List<ActionRun>? = null,
    val completedAtEpochMs: Long? = null
) {
    companion object {
        const val RECURRENCE_NONE = "none"
        const val RECURRENCE_DAILY = "daily"
        const val RECURRENCE_WEEKLY = "weekly"
    }
}

data class ActionStep(
    val id: String,
    val title: String,
    val toolName: String,
    val toolArguments: Map<String, Any> = emptyMap(),
    val continueOnError: Boolean = false
)

data class ActionRun(
    val id: String,
    val actionId: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val status: String,
    val stepResults: List<ActionStepResult>,
    val summary: String
) {
    companion object {
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
    }
}

data class ActionStepResult(
    val stepId: String,
    val title: String,
    val toolName: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val output: String,
    val isError: Boolean
)
