package com.example.nabu.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                val player = AudioPlayerManager.player ?: return
                if (player.getState() == PlayerState.PLAYING) {
                    player.pause()
                    PlaybackNotification.update(context, PlayerState.PAUSED)
                } else {
                    player.play()
                    PlaybackNotification.update(context, PlayerState.PLAYING)
                }
            }
            ACTION_STOP -> {
                AudioPlayerManager.onStop?.invoke()
                PlaybackNotification.update(context, PlayerState.IDLE)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.kokoro82m.PLAYBACK_TOGGLE"
        const val ACTION_STOP = "com.example.kokoro82m.PLAYBACK_STOP"
    }
}
