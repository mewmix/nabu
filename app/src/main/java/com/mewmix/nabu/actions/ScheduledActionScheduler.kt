package com.mewmix.nabu.actions

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.TimeUnit

object ScheduledActionScheduler {
    private val gson = Gson()
    internal var workEnqueuer: (Context, String, OneTimeWorkRequest) -> Unit = { context, uniqueName, request ->
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedule(context: Context, action: ScheduledAction) {
        val delayMs = (action.triggerAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val input = Data.Builder()
            .putString(ScheduledActionWorker.KEY_ACTION_ID, action.id)
            .putString(ScheduledActionWorker.KEY_TITLE, action.title)
            .putString(ScheduledActionWorker.KEY_INSTRUCTION, action.instruction)
            .putLong(ScheduledActionWorker.KEY_TRIGGER_AT, action.triggerAtEpochMs)
            .putString(ScheduledActionWorker.KEY_RECURRENCE, action.recurrence)
            .putString(ScheduledActionWorker.KEY_TOOL_NAME, action.toolName)
            .putString(ScheduledActionWorker.KEY_TOOL_ARGUMENTS_JSON, gson.toJson(action.toolArguments))
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduledActionWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(ScheduledActionWorker.TAG)
            .build()

        workEnqueuer(context.applicationContext, uniqueName(action.id), request)
        ScheduledActionStore.upsert(context.applicationContext, action)
    }

    fun createAndSchedule(
        context: Context,
        title: String,
        instruction: String,
        triggerAtEpochMs: Long,
        recurrence: String,
        toolName: String? = null,
        toolArguments: Map<String, Any> = emptyMap(),
        steps: List<ActionStep>? = null
    ): ScheduledAction {
        val action = ScheduledAction(
            id = UUID.randomUUID().toString(),
            title = title,
            instruction = instruction,
            triggerAtEpochMs = triggerAtEpochMs,
            recurrence = recurrence,
            toolName = toolName,
            toolArguments = toolArguments,
            steps = steps
        )
        schedule(context, action)
        return action
    }

    private fun uniqueName(actionId: String): String = "scheduled_action_$actionId"
}
