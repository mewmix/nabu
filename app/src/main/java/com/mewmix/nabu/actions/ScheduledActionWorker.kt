package com.mewmix.nabu.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mewmix.nabu.R
import kotlin.math.absoluteValue

class ScheduledActionWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val gson = Gson()

    override fun doWork(): Result {
        val actionId = inputData.getString(KEY_ACTION_ID).orEmpty()
        val fallbackAction = actionFromInputData(actionId)
        val action = ScheduledActionStore.find(applicationContext, actionId) ?: fallbackAction
        val executionRun = if (action.effectiveSteps().isNotEmpty()) {
            ActionPlanRunner.run(applicationContext, action) { appContext, call ->
                val step = action.effectiveSteps().firstOrNull {
                    it.toolName == call.toolName && it.toolArguments == call.arguments
                }
                if (call.toolName == ActionTools.SCHEDULED_AGENT_STEP_TOOL && step != null) {
                    ScheduledAgentStepExecutor.execute(appContext, action, step)
                } else {
                    ActionTools.execute(appContext, call)
                }
            }
        } else {
            null
        }

        if (executionRun != null) {
            ScheduledActionStore.recordRun(applicationContext, action.id, executionRun)
        }

        val storedAction = ScheduledActionStore.find(applicationContext, action.id) ?: action

        if (storedAction.recurrence == ScheduledAction.RECURRENCE_DAILY || storedAction.recurrence == ScheduledAction.RECURRENCE_WEEKLY) {
            val step = if (storedAction.recurrence == ScheduledAction.RECURRENCE_WEEKLY) {
                7L * 24L * 60L * 60L * 1000L
            } else {
                24L * 60L * 60L * 1000L
            }
            val next = storedAction.copy(
                triggerAtEpochMs = storedAction.triggerAtEpochMs + step,
                completedAtEpochMs = null
            )
            ScheduledActionScheduler.schedule(applicationContext, next)
        } else {
            val finalRun = executionRun ?: ActionRun(
                id = java.util.UUID.randomUUID().toString(),
                actionId = action.id,
                startedAtEpochMs = System.currentTimeMillis(),
                finishedAtEpochMs = System.currentTimeMillis(),
                status = ActionRun.STATUS_SUCCEEDED,
                stepResults = emptyList(),
                summary = action.instruction
            )
            ScheduledActionStore.complete(applicationContext, action.id, finalRun)
        }

        ensureChannel()
        NotificationManagerCompat.from(applicationContext).notify(
            action.id.hashCode().absoluteValue,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat_24)
                .setContentTitle(action.title.ifBlank { "Scheduled action" })
                .setContentText(notificationText(action.instruction, executionRun))
                .setStyle(NotificationCompat.BigTextStyle().bigText(notificationBody(action.instruction, executionRun)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )

        return Result.success()
    }

    private fun actionFromInputData(actionId: String): ScheduledAction {
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Scheduled action" }
        val instruction = inputData.getString(KEY_INSTRUCTION).orEmpty().ifBlank { "Action is due now." }
        val triggerAt = inputData.getLong(KEY_TRIGGER_AT, System.currentTimeMillis())
        val recurrence = inputData.getString(KEY_RECURRENCE).orEmpty().ifBlank { ScheduledAction.RECURRENCE_NONE }
        val toolName = inputData.getString(KEY_TOOL_NAME)?.trim().orEmpty()
        val toolArguments = parseToolArguments(inputData.getString(KEY_TOOL_ARGUMENTS_JSON))
        return ScheduledAction(
            id = actionId.ifBlank { java.util.UUID.randomUUID().toString() },
            title = title,
            instruction = instruction,
            triggerAtEpochMs = triggerAt,
            recurrence = recurrence,
            toolName = toolName.ifBlank { null },
            toolArguments = toolArguments
        )
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scheduled Actions",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private val mapType = TypeToken.getParameterized(
            Map::class.java,
            String::class.java,
            Any::class.java
        ).type

        const val TAG = "scheduled_action"
        const val CHANNEL_ID = "scheduled_action_channel"
        const val KEY_ACTION_ID = "action_id"
        const val KEY_TITLE = "title"
        const val KEY_INSTRUCTION = "instruction"
        const val KEY_TRIGGER_AT = "trigger_at"
        const val KEY_RECURRENCE = "recurrence"
        const val KEY_TOOL_NAME = "tool_name"
        const val KEY_TOOL_ARGUMENTS_JSON = "tool_arguments_json"
    }

    private fun parseToolArguments(rawJson: String?): Map<String, Any> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, Any>>(rawJson, mapType).orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun notificationText(
        instruction: String,
        executionRun: ActionRun?
    ): String {
        return when {
            executionRun == null -> instruction
            executionRun.status == ActionRun.STATUS_FAILED -> "Scheduled action failed"
            else -> "Scheduled action completed"
        }
    }

    private fun notificationBody(
        instruction: String,
        executionRun: ActionRun?
    ): String {
        if (executionRun == null) return instruction
        val stepLines = executionRun.stepResults.joinToString("\n") { result ->
            val clippedOutput = result.output.trim().ifBlank { "No output." }.take(240)
            val state = if (result.isError) "failed" else "completed"
            "${result.title} ($state): $clippedOutput"
        }
        return listOf(executionRun.summary, stepLines)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
