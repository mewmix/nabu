package com.mewmix.nabu.actions

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mewmix.nabu.utils.DatabaseManager

object ScheduledActionStore {
    private const val KEY_SCHEDULED_ACTIONS = "scheduled_actions"
    private const val MAX_RUN_HISTORY = 10
    private val gson = Gson()
    private val listType = TypeToken.getParameterized(List::class.java, ScheduledAction::class.java).type

    fun list(context: Context): List<ScheduledAction> {
        val raw = DatabaseManager.getSetting(context, KEY_SCHEDULED_ACTIONS).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<ScheduledAction>>(raw, listType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun upsert(context: Context, action: ScheduledAction) {
        val next = list(context)
            .filterNot { it.id == action.id }
            .plus(action)
            .sortedBy { it.triggerAtEpochMs }
        DatabaseManager.setSetting(context, KEY_SCHEDULED_ACTIONS, gson.toJson(next, listType))
    }

    fun recordRun(context: Context, actionId: String, run: ActionRun) {
        val next = list(context).map { action ->
            if (action.id != actionId) {
                action
            } else {
                val history = listOf(run)
                    .plus(action.runHistory.orEmpty())
                    .distinctBy { it.id }
                    .take(MAX_RUN_HISTORY)
                action.copy(lastRun = run, runHistory = history)
            }
        }
        DatabaseManager.setSetting(context, KEY_SCHEDULED_ACTIONS, gson.toJson(next, listType))
    }

    fun complete(context: Context, actionId: String, run: ActionRun) {
        val next = list(context).map { action ->
            if (action.id != actionId) {
                action
            } else {
                val history = listOf(run)
                    .plus(action.runHistory.orEmpty())
                    .distinctBy { it.id }
                    .take(MAX_RUN_HISTORY)
                action.copy(
                    lastRun = run,
                    runHistory = history,
                    completedAtEpochMs = run.finishedAtEpochMs
                )
            }
        }
        DatabaseManager.setSetting(context, KEY_SCHEDULED_ACTIONS, gson.toJson(next, listType))
    }

    fun find(context: Context, actionId: String): ScheduledAction? =
        list(context).firstOrNull { it.id == actionId }

    fun remove(context: Context, actionId: String) {
        val next = list(context).filterNot { it.id == actionId }
        DatabaseManager.setSetting(context, KEY_SCHEDULED_ACTIONS, gson.toJson(next, listType))
    }
}
