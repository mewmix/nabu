package com.example.nabu.speech

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.os.Build
import com.example.nabu.utils.DebugLogger

/**
 * Manages audio focus for the speech service.
 * Handles pause/resume on focus loss/gain.
 */
class AudioFocusManager(
    context: Context,
    private val onFocusLost: () -> Unit,
    private val onFocusGained: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var wasPausedByFocusLoss = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        DebugLogger.log("AudioFocus: Change event = $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                DebugLogger.log("AudioFocus: Transient loss, pausing")
                wasPausedByFocusLoss = true
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                DebugLogger.log("AudioFocus: Lost permanently, pausing")
                wasPausedByFocusLoss = false
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                DebugLogger.log("AudioFocus: Gained audio focus")
                if (wasPausedByFocusLoss) {
                    DebugLogger.log("AudioFocus: Resuming playback after focus gain")
                    wasPausedByFocusLoss = false
                    onFocusGained()
                }
            }
        }
    }

    private val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
    } else {
        null
    }

    /**
     * Request audio focus.
     * @return true if focus was granted, false otherwise.
     */
    fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DebugLogger.log("AudioFocus: Request result = ${if (granted) "GRANTED" else "DENIED"}")
        return granted
    }

    /**
     * Abandon audio focus when done playing.
     */
    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        DebugLogger.log("AudioFocus: Released audio focus")
    }
}
