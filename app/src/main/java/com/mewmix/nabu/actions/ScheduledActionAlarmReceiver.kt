package com.mewmix.nabu.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mewmix.nabu.utils.DebugLogger

class ScheduledActionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(ScheduledActionWorker.KEY_ACTION_ID).orEmpty()
        if (actionId.isBlank()) return

        val pendingResult = goAsync()
        Thread {
            try {
                DebugLogger.log("ScheduledActionAlarmReceiver executing actionId=$actionId")
                ScheduledActionExecutor.executeDueAction(context.applicationContext, actionId)
            } catch (t: Throwable) {
                DebugLogger.logErr("ScheduledActionAlarmReceiver failed actionId=$actionId", t)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
