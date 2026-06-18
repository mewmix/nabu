package com.mewmix.nabu.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mewmix.nabu.ChatActivity
import com.mewmix.nabu.R

class VoiceAssistantWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.nabu_voice_widget)
            views.setOnClickPendingIntent(
                R.id.nabu_voice_widget_label,
                voiceAssistantPendingIntent(context)
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun voiceAssistantPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ChatActivity::class.java).apply {
            action = ACTION_OPEN_VOICE_ASSISTANT
            putExtra(ChatActivity.EXTRA_START_VOICE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_OPEN_VOICE_ASSISTANT = "com.mewmix.nabu.action.OPEN_VOICE_ASSISTANT"
    }
}
