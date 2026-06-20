package com.mewmix.nabu.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.gson.Gson
import com.mewmix.nabu.utils.DebugLogger
import java.util.UUID
import java.util.concurrent.TimeUnit

object ScheduledActionScheduler {
    private const val IN_PROCESS_MAX_DELAY_MS = 15L * 60L * 1000L
    private const val EXACT_ALARM_SETTINGS_PROMPT_INTERVAL_MS = 60L * 60L * 1000L
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingInProcessRuns = mutableMapOf<String, Runnable>()
    private var lastExactAlarmSettingsPromptAt = 0L
    internal var workEnqueuer: (Context, String, OneTimeWorkRequest) -> Unit = { context, uniqueName, request ->
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }
    internal var alarmScheduler: (Context, ScheduledAction) -> Unit = ::scheduleExactAlarm
    internal var inProcessScheduler: (Context, ScheduledAction, Long) -> Unit = ::scheduleInProcess

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
        alarmScheduler(context.applicationContext, action)
        if (action.recurrence == ScheduledAction.RECURRENCE_NONE && delayMs <= IN_PROCESS_MAX_DELAY_MS) {
            inProcessScheduler(context.applicationContext, action, delayMs)
        }
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

    private fun scheduleInProcess(context: Context, action: ScheduledAction, delayMs: Long) {
        pendingInProcessRuns.remove(action.id)?.let(mainHandler::removeCallbacks)
        val appContext = context.applicationContext
        val runnable = Runnable {
            pendingInProcessRuns.remove(action.id)
            DebugLogger.log("ScheduledActionScheduler in-process due actionId=${action.id}")
            Thread {
                runCatching {
                    ScheduledActionExecutor.executeDueAction(appContext, action.id, action)
                }.onFailure {
                    DebugLogger.log("ScheduledActionScheduler in-process failed actionId=${action.id}: ${it::class.java.simpleName}: ${it.message}")
                }
            }.start()
        }
        pendingInProcessRuns[action.id] = runnable
        mainHandler.postDelayed(runnable, delayMs)
        DebugLogger.log("ScheduledActionScheduler in-process timer set actionId=${action.id} delayMs=$delayMs")
    }

    private fun scheduleExactAlarm(context: Context, action: ScheduledAction) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            DebugLogger.log("ScheduledActionScheduler exact alarm permission unavailable for actionId=${action.id}")
            promptForExactAlarmAccess(context)
            return
        }

        val intent = Intent(context, ScheduledActionAlarmReceiver::class.java)
            .putExtra(ScheduledActionWorker.KEY_ACTION_ID, action.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    action.triggerAtEpochMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    action.triggerAtEpochMs,
                    pendingIntent
                )
            }
            DebugLogger.log("ScheduledActionScheduler exact alarm set actionId=${action.id} triggerAt=${action.triggerAtEpochMs}")
        }.onFailure {
            DebugLogger.log("ScheduledActionScheduler exact alarm failed actionId=${action.id}: ${it.message}")
        }
    }

    private fun promptForExactAlarmAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val now = System.currentTimeMillis()
        if (now - lastExactAlarmSettingsPromptAt < EXACT_ALARM_SETTINGS_PROMPT_INTERVAL_MS) return
        lastExactAlarmSettingsPromptAt = now

        val appContext = context.applicationContext
        val exactAlarmIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            appContext.startActivity(exactAlarmIntent)
            DebugLogger.log("ScheduledActionScheduler opened exact alarm access settings")
        }.onFailure { exactAlarmError ->
            runCatching {
                appContext.startActivity(fallbackIntent)
                DebugLogger.log("ScheduledActionScheduler opened app settings for exact alarm access")
            }.onFailure { fallbackError ->
                DebugLogger.log(
                    "ScheduledActionScheduler could not open exact alarm settings: " +
                        "${exactAlarmError.message}; fallback=${fallbackError.message}"
                )
            }
        }
    }
}
