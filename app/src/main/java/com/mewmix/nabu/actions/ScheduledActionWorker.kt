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
import com.mewmix.nabu.tools.ToolCall
import kotlin.math.absoluteValue

class ScheduledActionWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val gson = Gson()

    override fun doWork(): Result {
        val actionId = inputData.getString(KEY_ACTION_ID).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Scheduled action" }
        val instruction = inputData.getString(KEY_INSTRUCTION).orEmpty().ifBlank { "Action is due now." }
        val triggerAt = inputData.getLong(KEY_TRIGGER_AT, System.currentTimeMillis())
        val recurrence = inputData.getString(KEY_RECURRENCE).orEmpty().ifBlank { ScheduledAction.RECURRENCE_NONE }
        val toolName = inputData.getString(KEY_TOOL_NAME)?.trim().orEmpty()
        val toolArguments = parseToolArguments(inputData.getString(KEY_TOOL_ARGUMENTS_JSON))

        val executionResult = if (toolName.isNotBlank()) {
            ActionTools.execute(
                applicationContext,
                ToolCall(toolName, toolArguments)
            ) ?: com.mewmix.nabu.tools.ToolResult(
                toolName = toolName,
                output = "Scheduled tool '$toolName' is unavailable.",
                isError = true
            )
        } else {
            null
        }

        ensureChannel()
        NotificationManagerCompat.from(applicationContext).notify(
            actionId.hashCode().absoluteValue,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat_24)
                .setContentTitle(title)
                .setContentText(notificationText(instruction, executionResult))
                .setStyle(NotificationCompat.BigTextStyle().bigText(notificationBody(instruction, executionResult)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )

        if (recurrence == ScheduledAction.RECURRENCE_DAILY || recurrence == ScheduledAction.RECURRENCE_WEEKLY) {
            val step = if (recurrence == ScheduledAction.RECURRENCE_WEEKLY) {
                7L * 24L * 60L * 60L * 1000L
            } else {
                24L * 60L * 60L * 1000L
            }
            val next = ScheduledAction(
                id = actionId,
                title = title,
                instruction = instruction,
                triggerAtEpochMs = triggerAt + step,
                recurrence = recurrence,
                toolName = toolName.ifBlank { null },
                toolArguments = toolArguments
            )
            ScheduledActionScheduler.schedule(applicationContext, next)
        } else {
            ScheduledActionStore.remove(applicationContext, actionId)
        }

        return Result.success()
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
        executionResult: com.mewmix.nabu.tools.ToolResult?
    ): String {
        return when {
            executionResult == null -> instruction
            executionResult.isError -> "Scheduled action failed"
            else -> "Scheduled action completed"
        }
    }

    private fun notificationBody(
        instruction: String,
        executionResult: com.mewmix.nabu.tools.ToolResult?
    ): String {
        if (executionResult == null) return instruction
        val clippedOutput = executionResult.output.trim().ifBlank { "No output." }
        return if (executionResult.isError) {
            "Tool ${executionResult.toolName} failed: $clippedOutput"
        } else {
            "Tool ${executionResult.toolName} result: $clippedOutput"
        }
    }
}
