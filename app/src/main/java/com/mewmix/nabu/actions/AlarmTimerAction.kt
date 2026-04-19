package com.mewmix.nabu.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.Locale

object AlarmTimerAction {
    data class LaunchResult(
        val message: String,
        val isError: Boolean = false
    )

    internal var canResolveIntent: (Context, Intent) -> Boolean = { context, intent ->
        intent.resolveActivity(context.packageManager) != null
    }

    internal var activityLauncher: (Context, Intent) -> Unit = { context, intent ->
        context.startActivity(intent)
    }

    internal fun resetForTesting() {
        canResolveIntent = { context, intent -> intent.resolveActivity(context.packageManager) != null }
        activityLauncher = { context, intent -> context.startActivity(intent) }
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, message: String): LaunchResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return LaunchResult(
                message = "Invalid alarm time. Hour must be 0-23 and minute 0-59.",
                isError = true
            )
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!canResolveIntent(context, intent)) {
            return LaunchResult("No alarm app is available on this device.", true)
        }

        return runCatching {
            activityLauncher(context, intent)
            LaunchResult(
                if (message.isBlank()) {
                    String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute)
                } else {
                    String.format(Locale.US, "Alarm set for %02d:%02d with message: '%s'.", hour, minute, message)
                }
            )
        }.getOrElse {
            LaunchResult("Failed to set alarm: ${it.message}", true)
        }
    }

    fun setTimer(context: Context, seconds: Int, message: String): LaunchResult {
        if (seconds <= 0) {
            return LaunchResult(
                message = "Invalid timer duration. Seconds must be greater than 0.",
                isError = true
            )
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!canResolveIntent(context, intent)) {
            return LaunchResult("No timer app is available on this device.", true)
        }

        return runCatching {
            activityLauncher(context, intent)
            LaunchResult(
                if (message.isBlank()) {
                    "Timer set for $seconds seconds."
                } else {
                    "Timer set for $seconds seconds with message: '$message'."
                }
            )
        }.getOrElse {
            LaunchResult("Failed to set timer: ${it.message}", true)
        }
    }
}
