package com.mewmix.nabu.actions

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

object ScheduledActionScheduler {
    fun schedule(context: Context, action: ScheduledAction) {
        val delayMs = (action.triggerAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val input = Data.Builder()
            .putString(ScheduledActionWorker.KEY_ACTION_ID, action.id)
            .putString(ScheduledActionWorker.KEY_TITLE, action.title)
            .putString(ScheduledActionWorker.KEY_INSTRUCTION, action.instruction)
            .putLong(ScheduledActionWorker.KEY_TRIGGER_AT, action.triggerAtEpochMs)
            .putString(ScheduledActionWorker.KEY_RECURRENCE, action.recurrence)
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduledActionWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(ScheduledActionWorker.TAG)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(action.id), ExistingWorkPolicy.REPLACE, request)
        ScheduledActionStore.upsert(context.applicationContext, action)
    }

    fun createAndSchedule(
        context: Context,
        title: String,
        instruction: String,
        triggerAtEpochMs: Long,
        recurrence: String
    ): ScheduledAction {
        val action = ScheduledAction(
            id = UUID.randomUUID().toString(),
            title = title,
            instruction = instruction,
            triggerAtEpochMs = triggerAtEpochMs,
            recurrence = recurrence
        )
        schedule(context, action)
        return action
    }

    private fun uniqueName(actionId: String): String = "scheduled_action_$actionId"
}
