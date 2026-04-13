package com.mewmix.nabu.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object AlarmTimerAction {
    fun setAlarm(context: Context, hour: Int, minute: Int, message: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Alarm set for $hour:$minute with message: '$message'."
        }.getOrElse { "Failed to set alarm: ${it.message}" }
    }

    fun setTimer(context: Context, seconds: Int, message: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Timer set for $seconds seconds with message: '$message'."
        }.getOrElse { "Failed to set timer: ${it.message}" }
    }
}