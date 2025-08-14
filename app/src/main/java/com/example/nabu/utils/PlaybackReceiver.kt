package com.example.nabu.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val player = AudioPlayerManager.player ?: return
            if (player.getState() == PlayerState.PLAYING) {
                player.pause()
                PlaybackNotification.update(context, PlayerState.PAUSED)
            } else {
                player.play()
                PlaybackNotification.update(context, PlayerState.PLAYING)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.kokoro82m.PLAYBACK_TOGGLE"
    }
}
